package com.thinkaurelius.titan.diskstorage.cassandra.astyanax;

import com.google.common.collect.ImmutableMap;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.connectionpool.OperationResult;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.connectionpool.exceptions.NotFoundException;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.query.RowQuery;
import com.netflix.astyanax.retry.RetryPolicy;
import com.netflix.astyanax.serializers.ByteBufferSerializer;
import com.thinkaurelius.titan.diskstorage.StorageException;
import com.thinkaurelius.titan.diskstorage.TemporaryStorageException;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.*;
import static com.thinkaurelius.titan.diskstorage.cassandra.CassandraTransaction.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AstyanaxOrderedKeyColumnValueStore implements
        KeyColumnValueStore {

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);


    private final Keyspace keyspace;
	private final String columnFamilyName;
	private final ColumnFamily<ByteBuffer, ByteBuffer> columnFamily;
	private final RetryPolicy retryPolicy;
    private final AstyanaxStoreManager storeManager;
	

	AstyanaxOrderedKeyColumnValueStore(String columnFamilyName, Keyspace keyspace,
                      AstyanaxStoreManager storeManager, RetryPolicy retryPolicy) {
		this.keyspace = keyspace;
		this.columnFamilyName = columnFamilyName;
		this.retryPolicy = retryPolicy;
        this.storeManager=storeManager;

		columnFamily = new ColumnFamily<ByteBuffer, ByteBuffer>(
				this.columnFamilyName,
				ByteBufferSerializer.get(),
				ByteBufferSerializer.get());
	}
	


    ColumnFamily<ByteBuffer,ByteBuffer> getColumnFamily() {
        return columnFamily;
    }
    
	@Override
	public void close() throws StorageException {
		//Do nothing
	}

	@Override
	public ByteBuffer get(ByteBuffer key, ByteBuffer column,
			StoreTransaction txh) throws StorageException {
		try {
			OperationResult<Column<ByteBuffer>> result = 
				keyspace.prepareQuery(columnFamily)
					.setConsistencyLevel(getTx(txh).getReadConsistencyLevel().getAstyanaxConsistency())
					.withRetryPolicy(retryPolicy.duplicate())
					.getKey(key).getColumn(column).execute();
			return result.getResult().getByteBufferValue();
		} catch (NotFoundException e) {
			return null;
		} catch (ConnectionException e) {
			throw new TemporaryStorageException(e);
		}
	}

	@Override
	public boolean containsKeyColumn(ByteBuffer key, ByteBuffer column,
			StoreTransaction txh) throws StorageException {
		return null != get(key, column, txh);
	}

	@Override
	public boolean containsKey(ByteBuffer key, StoreTransaction txh) throws StorageException {
		try {
			// See getSlice() below for a warning suppression justification
			@SuppressWarnings("rawtypes")
			RowQuery rq = (RowQuery)keyspace.prepareQuery(columnFamily)
								.withRetryPolicy(retryPolicy.duplicate())
								.setConsistencyLevel(getTx(txh).getReadConsistencyLevel().getAstyanaxConsistency())
								.getKey(key);
			@SuppressWarnings("unchecked")
			OperationResult<ColumnList<ByteBuffer>> r = rq.withColumnRange(EMPTY, EMPTY, false, 1).execute();
			return 0 < r.getResult().size();
		} catch (ConnectionException e) {
			throw new TemporaryStorageException(e);
		}
	}

	@Override
	public List<Entry> getSlice(ByteBuffer key, ByteBuffer columnStart,
			ByteBuffer columnEnd, int limit, StoreTransaction txh) throws StorageException {
		
		/*
		 * The following hideous cast dance avoids a type-erasure error in the
		 * RowQuery<K, V> type that emerges when K=V=ByteBuffer. Specifically,
		 * these two methods erase to the same signature after generic reduction
		 * during compilation:
		 * 
		 * RowQuery<K, C> withColumnRange(C startColumn, C endColumn, boolean
		 * reversed, int count) RowQuery<K, C> withColumnRange(ByteBuffer
		 * startColumn, ByteBuffer endColumn, boolean reversed, int count)
		 * 
		 * 
		 * The compiler substitutes ByteBuffer=C for both startColumn and
		 * endColumn, compares it to its identical twin with that type
		 * hard-coded, and dies.
		 * 
		 * Here's the compiler error I received when attempting to compile this
		 * code without the following casts. I used Oracle JDK 6 Linux x86_64.
		 * 
		 * AstyanaxOrderedKeyColumnValueStore.java:[108,4] reference to
		 * withColumnRange is ambiguous, both method
		 * withColumnRange(C,C,boolean,int) in
		 * com.netflix.astyanax.query.RowQuery<java.nio.ByteBuffer,java.nio.ByteBuffer>
		 * and method
		 * withColumnRange(java.nio.ByteBuffer,java.nio.ByteBuffer,boolean,int)
		 * in
		 * com.netflix.astyanax.query.RowQuery<java.nio.ByteBuffer,java.nio.ByteBuffer>
		 * match
		 * 
		 */
		@SuppressWarnings("rawtypes")
		RowQuery rq = (RowQuery)keyspace.prepareQuery(columnFamily)
						.setConsistencyLevel(getTx(txh).getReadConsistencyLevel().getAstyanaxConsistency())
						.withRetryPolicy(retryPolicy.duplicate())
						.getKey(key);
//		RowQuery<ByteBuffer, ByteBuffer> rq = keyspace.prepareQuery(columnFamily).getKey(key);
		rq.withColumnRange(columnStart, columnEnd, false, limit + 1);
		
		OperationResult<ColumnList<ByteBuffer>> r;
		try {
			@SuppressWarnings("unchecked")
			OperationResult<ColumnList<ByteBuffer>> tmp = (OperationResult<ColumnList<ByteBuffer>>)rq.execute();
			r = tmp;
		} catch (ConnectionException e) {
			throw new TemporaryStorageException(e);
		}
		
		List<Entry> result = new ArrayList<Entry>(r.getResult().size());
		
		int i = 0;
		
		for (Column<ByteBuffer> c : r.getResult()) {
			ByteBuffer colName = c.getName();
			
			if (colName.equals(columnEnd)) {
				break;
			}
			
			result.add(new Entry(colName, c.getByteBufferValue()));
			
			if (++i == limit) {
				break;
			}
		}
		
		return result;
	}

	@Override
	public List<Entry> getSlice(ByteBuffer key, ByteBuffer columnStart,
			ByteBuffer columnEnd, StoreTransaction txh) throws StorageException {
		return getSlice(key, columnStart, columnEnd, Integer.MAX_VALUE - 1, txh);
	}

    @Override
    public void mutate(ByteBuffer key, List<Entry> additions,
                       List<ByteBuffer> deletions, StoreTransaction txh) throws StorageException {
        Map<ByteBuffer, Mutation> mutations = ImmutableMap.of(key,new
                Mutation(additions, deletions));
        mutateMany(mutations, txh);
    }

    public void mutateMany(Map<ByteBuffer, Mutation> mutations,
			StoreTransaction txh) throws StorageException {
        storeManager.mutateMany(ImmutableMap.of(columnFamilyName,mutations),txh);
	}

    @Override
    public void acquireLock(ByteBuffer key, ByteBuffer column, ByteBuffer expectedValue, StoreTransaction txh) throws StorageException {
        throw new UnsupportedOperationException();
    }

    @Override
    public RecordIterator<ByteBuffer> getKeys(StoreTransaction txh) throws StorageException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ByteBuffer[] getLocalKeyPartition() throws StorageException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getName() {
        return columnFamilyName;
    }
}
