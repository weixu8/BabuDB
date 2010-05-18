/*
 * Copyright (c) 2010, Konrad-Zuse-Zentrum fuer Informationstechnik Berlin
 * 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 * 
 * Redistributions of source code must retain the above copyright notice, this 
 * list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright notice, 
 * this list of conditions and the following disclaimer in the documentation 
 * and/or other materials provided with the distribution.
 * Neither the name of the Konrad-Zuse-Zentrum fuer Informationstechnik Berlin 
 * nor the names of its contributors may be used to endorse or promote products 
 * derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 */
/*
 * AUTHORS: Felix Langner (ZIB)
 */
package org.xtreemfs.babudb.replication.service.clients;

import org.xtreemfs.babudb.lsmdb.LSN;
import org.xtreemfs.foundation.flease.comm.FleaseMessage;
import org.xtreemfs.foundation.oncrpc.client.RPCResponse;

/**
 * Client to access the fundamental services of an participating server.
 *
 * @author flangner
 * @since 04/13/2010
 */
public interface ConditionClient extends ClientInterface {

    /**
     * The {@link LSN} of the latest written LogEntry.
     * 
     * @return the {@link RPCResponse} receiving a state as {@link LSN}.
     */
    public RPCResponse<LSN> state();

    /**
     * The local time-stamp of the registered participant.
     * 
     * @return the {@link RPCResponse} receiving a time-stamp as {@link Long}.
     */
    public RPCResponse<Long> time();

    /**
     * Sends a {@link FleaseMessage} to the client.
     * 
     * @return the {@link RPCResponse} as proxy for the response.
     */
    public RPCResponse<Object> flease(FleaseMessage message);

    /**
     * Updates the latest LSN of the slave at the master.
     * 
     * @param lsn
     * @return the {@link RPCResponse}.
     */
    public RPCResponse<?> heartbeat(LSN lsn);

    
    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString();
}