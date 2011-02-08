/*
 * Copyright (c) 2010-2011, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist,
 *                     Felix Hupfeld, Felix Langner, Zuse Institute Berlin
 * 
 * Licensed under the BSD License, see LICENSE file for details.
 * 
 */
/*
 * AUTHORS: Felix Langner (ZIB)
 */
package org.xtreemfs.babudb.replication.control;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import org.xtreemfs.babudb.api.exception.BabuDBException;
import org.xtreemfs.babudb.replication.service.ServiceToControlInterface;
import org.xtreemfs.foundation.LifeCycleThread;
import org.xtreemfs.foundation.TimeSync;
import org.xtreemfs.foundation.flease.Flease;
import org.xtreemfs.foundation.logging.Logging;

/**
 * <p>
 * Provides the automatic failover capability of the replication mechanism.
 * Continuously updates the current lease holder via {@link Flease}.
 * Changes the state of the replication mechanism.
 * </p>
 * 
 * @author flangner
 * @since 04/15/2010
 */
public class ReplicationController extends LifeCycleThread implements ControlListener {

    /** flag that determines if the replication is suspended currently */
    private final AtomicBoolean suspended = new AtomicBoolean(false);
    
    /** necessary to support mutual exclusion on failover */
    private final AtomicBoolean failoverInProgress = new AtomicBoolean(false);
    
    /** necessary to support mutual exclusion on handover */
    private final AtomicBoolean handoverInProgress = new AtomicBoolean(false);
    
    /** the leaseholder recognized for the failover actually executed */
    private InetAddress         newLeaseHolder = null;
    
    /** boolean to determine if the thread shall be terminated */
    private volatile boolean    quit = true;
    
    /** the local address used for the net-communication */
    private final InetAddress   thisAddress;
    
    /** methods provided by the service-layer */
    private final ServiceToControlInterface serviceInterface;
    
    /** the lease containing instance */
    private final FleaseHolder              fleaseHolder;
    
    /** the parent layer, needed to exclude temporarily from {@link Flease} */
    private final ControlLayer              ctrlLayer;
        
    public ReplicationController(FleaseHolder leaseStatus,
            ServiceToControlInterface serviceInterface, InetAddress own, 
            ControlLayer ctrlLayer) {
        super("ReplicationController");
        
        this.ctrlLayer = ctrlLayer;
        this.thisAddress = own;
        this.serviceInterface = serviceInterface;
        this.fleaseHolder = leaseStatus;
    }
    
    /**
     * @return true, if the replication is currently suspended, false otherwise.
     */
    public boolean isSuspended() {
        return this.suspended.get();
    }

    /**
     * Method may block if this server performs a failover or a handover at the
     * time of execution.
     * 
     * @return true, if this server is the leaseholder, false otherwise.
     * 
     * @exception InterruptedException if waiting was interrupted.
     */
    public boolean hasLease() throws InterruptedException {
        boolean result;
        synchronized (this.failoverInProgress) {
            while ((result = (!this.handoverInProgress.get() && 
                    this.ctrlLayer.amIMaster())) && 
                    this.failoverInProgress.get()) {
                
                    this.failoverInProgress.wait();
            }
            return result;
        } 
    }
    
    /**
     * This methods stops the currently running fail/hand-over interruptive. 
     */
    public void shutdown() {
        this.quit = true;
        synchronized (this.failoverInProgress) {
            this.failoverInProgress.set(false);
            this.interrupt();
            this.failoverInProgress.notifyAll();
        }
    }
    
/*
 * Overridden methods
 */
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.control.ControlListener#notifyForSuspension()
     */
    @Override
    public void notifyForSuspension() {
        synchronized (this.suspended) {
            if (this.suspended.get()) return;
            
         // part one: acquire the lock
            synchronized (this.fleaseHolder) {
                if (this.ctrlLayer.amIMaster()) {
                    // set the handover in progress
                    handoverInProgress.set(true);
                    
                    // interrupt running failover and suspend it
                    synchronized (failoverInProgress) {
                        failoverInProgress.set(false);
                        this.interrupt();   
                    }
                } else {
                    suspendReplication();
                }
            }

            // part two: process the handover
            synchronized(handoverInProgress) {
                // check if this server should perform a handover
                if (handoverInProgress.get()) {
                    Logging.logMessage(Logging.LEVEL_INFO, this, 
                            "The lease is handed over to '%s'.", "anyone");
                        
                    // exclude from Flease
                    this.ctrlLayer.exitFlease();
                    
                    // suspend the replication on this server
                    suspendReplication();
                    
                    // wait for a lease-timeout to be completed
                    long diff = 0;
                    long timeout = this.fleaseHolder.getLeaseTimeout();
                    if (this.ctrlLayer.amIMaster()) {
                        diff = timeout - TimeSync.getGlobalTime();
                    }
                    if (diff > 0) {
                        try {
                            this.handoverInProgress.wait(diff);
                        } catch (InterruptedException e) { /* I don't care */ }
                    }
                    
                    // join Flease again
                    this.ctrlLayer.joinFlease();
                    
                    this.handoverInProgress.set(false);
                }
            }
        } 
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.control.ControlListener#notifyForHandover()
     */
    @Override
    public void notifyForHandover() {        
        if (this.handoverInProgress.get() && !this.ctrlLayer.amIMaster()) {
            synchronized (this.handoverInProgress) {
                if (this.handoverInProgress.compareAndSet(true, false)) {
                    this.handoverInProgress.notify();
                }
            }
        }
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.replication.control.ControlListener#notifyForFailover(java.net.InetAddress)
     */
    @Override
    public void notifyForFailover(InetAddress leaseholder) {
        synchronized (this.failoverInProgress) {
            this.newLeaseHolder = leaseholder;
            
            if (this.failoverInProgress.getAndSet(true)) {
                this.interrupt();
            } else {
                this.failoverInProgress.notifyAll();
            }
        }
    }
    
    /*
     * (non-Javadoc)
     * @see java.lang.Thread#run()
     */
    @Override
    public void run() {
        this.quit = false;
        InetAddress newMaster = null;
        notifyStarted();
        
        while(!quit) {
            synchronized (failoverInProgress) {
                // wait for a failover request
                while (!failoverInProgress.get()) {
                    try {
                        failoverInProgress.wait();
                    } catch (InterruptedException e) {
                        if (quit) break;
                    }
                }
                if (quit) break;
                
                assert (newLeaseHolder != null);
                newMaster = newLeaseHolder;
                newLeaseHolder = null;
            }
                
            // process the failover request
            suspendReplication();
            if (this.thisAddress.equals(newMaster)) {
                try {
                    // try to become a master; leads to a handover on failure
                    becomeMaster();
                } catch (InterruptedException ie) {
                    /* ignored */
                } catch (Exception e) {
                    Logging.logMessage(Logging.LEVEL_ERROR, this, 
                            "failover did not work, because: %s ", 
                            e.getMessage());
                    Logging.logError(Logging.LEVEL_INFO, this, e);
                    notifyForSuspension();
                }
            } else if (newMaster != null){
                // become a slave
                becomeSlave(newMaster);
            } 
            
            // failover finished
            synchronized (failoverInProgress) {
                if (failoverInProgress.get() && newLeaseHolder == null ) {
                    this.suspended.set(false);
                    failoverInProgress.set(false);
                    failoverInProgress.notifyAll();
                }
            }
        }
        notifyStopped();
    }
    
/*
 * private methods
 */
    
//    /**
//     * This server gives up its master privileges. The lease is handed over
//     * to a server depending on the latest acknowledged LSN received by it.
//     * The local instance will also become slave of the new master, if handover
//     * was successful.
//     * FIXME
//     * @throws InterruptedException
//     * 
//     * @return true, if handover was successful, false if this server loast the
//     *         ownership of the lease because of a timeout.
//     */
//    private boolean handover() throws InterruptedException {
//        boolean success = false;
//        InetSocketAddress newOwner;
//        int index = 0;
//        List<InetSocketAddress> clients = ctrlLayer.getClients();
//        do {
//            newOwner = clients.get(index++);
//            index %= clients.size();
//            success = this.ctrlLayer.handOverLease(
//                    new ASCIIString(FleaseHolder.getIdentity(newOwner)));
//        } while (hasLease());
//        
//        if (success) {
//            becomeSlave(newOwner.getAddress());
//        }
//        
//        return success;
//    }
    
    /**
     * This server has to become the new master.
     * 
     * @throws InterruptedException 
     * @throws BabuDBException 
     * @throws IOException 
     */
    private void becomeMaster() throws InterruptedException, BabuDBException, 
            IOException {
        
        Logging.logMessage(Logging.LEVEL_INFO, this, 
                "Becoming the replication master.");
        this.serviceInterface.synchronize();
        this.ctrlLayer.getTimeDriftDetectorControl().start();
        this.serviceInterface.changeMaster(null);
    }
    
    /**
     * Another server has become the master and this one has to obey.
     * @param masterAddress
     */
    private void becomeSlave(InetAddress masterAddress) {
        Logging.logMessage(Logging.LEVEL_INFO, this, "Becoming a slave for %s.", 
                masterAddress.toString());
        
        this.serviceInterface.changeMaster(masterAddress);
    }
    
    /**
     * Resets the replication mechanisms state and prevents it from becoming 
     * penetrated by replication requests until the next failover.
     */
    private synchronized void suspendReplication() {
        if (this.suspended.compareAndSet(false, true)) {
            this.serviceInterface.reset();
            this.ctrlLayer.getTimeDriftDetectorControl().stop();
        }
    }
}