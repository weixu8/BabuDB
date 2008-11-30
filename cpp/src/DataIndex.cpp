// Copyright (c) 2008, Felix Hupfeld, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist, Zuse Institute Berlin.
// Licensed under the BSD License, see LICENSE file for details.

#include "DataIndex.h"
#include "util.h"

#include "Log.h"
#include "LogSection.h"
#include "LogIndex.h"
#include "ImmutableIndex.h"
#include "babudb/LookupIterator.h"
#include "babudb/KeyOrder.h"
using namespace babudb;

#include <algorithm>

#include "yield/platform/disk_path.h"
#include "yield/platform/directory_walker.h"
#include "yield/platform/memory_mapped_file.h"
using namespace YIELD_NS;

DataIndex::DataIndex(const string& name, KeyOrder& order)
	: order(order), immutable_index(NULL), name_prefix(name), tail(NULL) {}

DataIndex::DataIndex(const DataIndex& other)
	: order(other.order), log_indices(other.log_indices),
	immutable_index(other.immutable_index), name_prefix(other.name_prefix), tail(other.tail) {}


DataIndex::~DataIndex() {
	delete immutable_index;

	for(vector<LogIndex*>::iterator i = log_indices.begin(); i != log_indices.end(); ++i)
		delete *i;
}

typedef vector<pair<YIELD_NS::DiskPath,pair<lsn_t,bool> > > DiskIndices;

static DiskIndices scanAvailableImmutableIndices(string& name_prefix, KeyOrder& order) {
	DiskIndices results;

	pair<YIELD_NS::DiskPath,YIELD_NS::DiskPath> dir_prefix = YIELD_NS::DiskPath(name_prefix).split();
	YIELD_NS::DirectoryWalker walker(dir_prefix.first);

	while(walker.hasNext()) {
		auto_ptr<YIELD_NS::DirectoryEntry> entry = walker.getNext();

		lsn_t lsn;
		if(matchFilename(entry->getPath(), dir_prefix.second.getHostCharsetPath(), "idx", lsn)) {
			auto_ptr<YIELD_NS::MemoryMappedFile> file(new YIELD_NS::MemoryMappedFile(entry->getPath(), 1, DOOF_SYNC));
			ImmutableIndex index(file,order,lsn);
			results.push_back(make_pair(entry->getPath(),make_pair(lsn, index.checkHealth())));
		}
	}

	return results;
}

static pair<YIELD_NS::DiskPath,lsn_t> findLatestIntactImmutableIndex(DiskIndices& on_disk) {
	// find latest intact ImmutableIndex
	lsn_t latest_intact_lsn = 0;
	YIELD_NS::DiskPath latest_intact_path("");

	for(DiskIndices::iterator i = on_disk.begin(); i != on_disk.end(); ++i) {
		if(i->second.second && i->second.first > latest_intact_lsn) {  // healthy and newer than the current latest
			latest_intact_lsn = i->second.first;
			latest_intact_path = i->first;
		}
	}

	return make_pair(latest_intact_path, latest_intact_lsn);
}

lsn_t DataIndex::loadLatestIntactImmutableIndex() {
	DiskIndices on_disk = scanAvailableImmutableIndices(name_prefix, order);

	pair<YIELD_NS::DiskPath,lsn_t> latest = findLatestIntactImmutableIndex(on_disk);

	// load it

	if(latest.second > 0) {
		auto_ptr<YIELD_NS::MemoryMappedFile> file(new YIELD_NS::MemoryMappedFile(latest.first, 1024 * 1024, DOOF_SYNC));
		immutable_index = new ImmutableIndex(file,order,latest.second);
		immutable_index->load();
	}

	return latest.second;
}

void DataIndex::cleanup(lsn_t from_lsn, const string& to) {
	DiskIndices on_disk = scanAvailableImmutableIndices(name_prefix, order);
	pair<YIELD_NS::DiskPath,lsn_t> latest = findLatestIntactImmutableIndex(on_disk);

	for(DiskIndices::iterator i = on_disk.begin(); i != on_disk.end(); ++i) {
		if(i->first != latest.first) {// not the latest intact index
			pair<YIELD_NS::DiskPath,YIELD_NS::DiskPath> parts = i->first.split();
			YIELD_NS::DiskOperations::rename(i->first, YIELD_NS::DiskPath(to) + parts.second);
		}
	}
}

Data DataIndex::lookup(const Data& key) {
	for(vector<LogIndex*>::iterator i = log_indices.begin();
		i != log_indices.end(); ++i) {
		Data result = (*i)->lookup(key);
		if(result.isDeleted())
			return Data::Empty();

		if(!result.isEmpty())
			return result;
	}

	if(immutable_index)
		return immutable_index->lookup(key);

	return Data::Empty();
}

LookupIterator DataIndex::lookup(const Data& lower, const Data& upper) {
	return LookupIterator(log_indices, immutable_index, order, lower, upper);
}

LogIndex* DataIndex::getMigratableIndex() {
	if(log_indices.size() == 0)
		return NULL;
	else {
		ASSERT_TRUE(tail != log_indices.back());
		return log_indices.back();
	}
}

ImmutableIndex* DataIndex::getMigrationBase() {
	return immutable_index;
}

void DataIndex::advanceImmutableIndex(ImmutableIndex* new_iindex) {
	if(immutable_index)
		delete immutable_index;
	immutable_index = new_iindex;

	LogIndex* obsolete_logindex = log_indices.back();
	delete obsolete_logindex;
	log_indices.pop_back();
}

void DataIndex::advanceTail(lsn_t current_lsn) {
	tail = new LogIndex(order,current_lsn);
	log_indices.insert(log_indices.begin(),tail);
}

LogIndex* DataIndex::getTail() {
	ASSERT_TRUE(tail != NULL);
	return tail;
}

void DataIndexOperationTarget::set(const string& index, const Data& key, const Data& value) {
	idcs[index]->getTail()->add(key, value);
}

void DataIndexOperationTarget::remove(const string& index, const Data& key) {
	idcs[index]->getTail()->add(key, Data::Deleted());
}
