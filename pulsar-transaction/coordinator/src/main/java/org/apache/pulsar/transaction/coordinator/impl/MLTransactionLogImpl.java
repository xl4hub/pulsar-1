/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.transaction.coordinator.impl;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.bookkeeper.mledger.AsyncCallbacks;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.bookkeeper.mledger.ManagedLedger;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.ManagedLedgerFactory;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.pulsar.common.allocator.PulsarByteBufAllocator;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSubscribe;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.util.protobuf.ByteBufCodedInputStream;
import org.apache.pulsar.common.util.protobuf.ByteBufCodedOutputStream;
import org.apache.pulsar.transaction.coordinator.TransactionCoordinatorID;
import org.apache.pulsar.transaction.coordinator.TransactionLog;
import org.apache.pulsar.transaction.coordinator.TransactionLogReplayCallback;
import org.apache.pulsar.transaction.coordinator.proto.PulsarTransactionMetadata.TransactionMetadataEntry;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.SpscArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MLTransactionLogImpl implements TransactionLog {

    private static final Logger log = LoggerFactory.getLogger(MLTransactionLogImpl.class);

    private final ManagedLedger managedLedger;

    private final static String TRANSACTION_LOG_PREFIX = NamespaceName.SYSTEM_NAMESPACE + "/transaction-log-";

    private final ManagedCursor cursor;

    private final static String TRANSACTION_SUBSCRIPTION_NAME = "transaction.subscription";

    private final SpscArrayQueue<Entry> entryQueue;

    //this is for replay
    private final PositionImpl lastConfirmedEntry;

    private final long tcId;

    private final String topicName;

    public MLTransactionLogImpl(TransactionCoordinatorID tcID,
                                ManagedLedgerFactory managedLedgerFactory) throws Exception {
        this.topicName = TRANSACTION_LOG_PREFIX + tcID;
        this.tcId = tcID.getId();
        this.managedLedger = managedLedgerFactory.open(topicName);
        this.cursor =  managedLedger.openCursor(TRANSACTION_SUBSCRIPTION_NAME,
                CommandSubscribe.InitialPosition.Earliest);
        this.entryQueue = new SpscArrayQueue<>(2000);
        this.lastConfirmedEntry = (PositionImpl) managedLedger.getLastConfirmedEntry();
    }

    @Override
    public void replayAsync(TransactionLogReplayCallback transactionLogReplayCallback) {
        new TransactionLogReplayer(transactionLogReplayCallback).start();
    }

    private void readAsync(int numberOfEntriesToRead,
                           AsyncCallbacks.ReadEntriesCallback readEntriesCallback) {
        cursor.asyncReadEntries(numberOfEntriesToRead, readEntriesCallback, System.nanoTime());
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();

        managedLedger.asyncClose(new AsyncCallbacks.CloseCallback() {
            @Override
            public void closeComplete(Object ctx) {
                log.info("Transaction log with tcId : {} close managedLedger successful!", tcId);
                completableFuture.complete(null);
            }

            @Override
            public void closeFailed(ManagedLedgerException exception, Object ctx) {
                log.error("Transaction log with tcId : {} close managedLedger fail!", tcId);
                completableFuture.completeExceptionally(exception);
            }
        }, null);

        return completableFuture;
    }

    @Override
    public CompletableFuture<Position> append(TransactionMetadataEntry transactionMetadataEntry) {
        int transactionMetadataEntrySize = transactionMetadataEntry.getSerializedSize();
        ByteBuf buf = PulsarByteBufAllocator.DEFAULT.buffer(transactionMetadataEntrySize, transactionMetadataEntrySize);
        ByteBufCodedOutputStream outStream = ByteBufCodedOutputStream.get(buf);
        CompletableFuture<Position> completableFuture = new CompletableFuture<>();
        try {
            transactionMetadataEntry.writeTo(outStream);
            managedLedger.asyncAddEntry(buf, new AsyncCallbacks.AddEntryCallback() {
                @Override
                public void addComplete(Position position, Object ctx) {
                    buf.release();
                    completableFuture.complete(position);
                }

                @Override
                public void addFailed(ManagedLedgerException exception, Object ctx) {
                    log.error("Transaction log write transaction operation error", exception);
                    buf.release();
                    completableFuture.completeExceptionally(exception);
                }
            } , null);
        } catch (IOException e) {
            log.error("Transaction log write transaction operation error", e);
            completableFuture.completeExceptionally(e);
        } finally {
            outStream.recycle();
        }
        return completableFuture;
    }

    public CompletableFuture<Void> deletePosition(List<Position> positions) {
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        this.cursor.asyncDelete(positions, new AsyncCallbacks.DeleteCallback() {
            @Override
            public void deleteComplete(Object position) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}][{}] Deleted message at {}", topicName,
                            TRANSACTION_SUBSCRIPTION_NAME, position);
                }
                completableFuture.complete(null);
            }

            @Override
            public void deleteFailed(ManagedLedgerException exception, Object ctx) {
                log.warn("[{}][{}] Failed to delete message at {}", topicName,
                        TRANSACTION_SUBSCRIPTION_NAME, ctx, exception);
                completableFuture.completeExceptionally(exception);
            }
        }, null);
        return completableFuture;
    }

    class TransactionLogReplayer {

        private FillEntryQueueCallback fillEntryQueueCallback;
        private long currentLoadEntryId;
        private TransactionLogReplayCallback transactionLogReplayCallback;

        TransactionLogReplayer(TransactionLogReplayCallback transactionLogReplayCallback) {
            this.fillEntryQueueCallback = new FillEntryQueueCallback();
            this.transactionLogReplayCallback = transactionLogReplayCallback;
        }

        public void start() {
            if (((PositionImpl) cursor.getMarkDeletedPosition()).compareTo(lastConfirmedEntry) == 0) {
                this.transactionLogReplayCallback.replayComplete();
                return;
            }
            while (currentLoadEntryId < lastConfirmedEntry.getEntryId()) {
                fillEntryQueueCallback.fillQueue();
                Entry entry = entryQueue.poll();
                if (entry != null) {
                    ByteBuf buffer = entry.getDataBuffer();
                    currentLoadEntryId = entry.getEntryId();
                    ByteBufCodedInputStream stream = ByteBufCodedInputStream.get(buffer);
                    TransactionMetadataEntry.Builder transactionMetadataEntryBuilder =
                            TransactionMetadataEntry.newBuilder();
                    TransactionMetadataEntry transactionMetadataEntry;
                    try {
                        transactionMetadataEntry =
                                transactionMetadataEntryBuilder.mergeFrom(stream, null).build();
                    } catch (IOException e) {
                        log.error(e.getMessage(), e);
                        throw new RuntimeException("TransactionLog convert entry error : ", e);
                    }
                    transactionLogReplayCallback.handleMetadataEntry(entry.getPosition(), transactionMetadataEntry);
                    entry.release();
                    transactionMetadataEntry.recycle();
                    transactionMetadataEntryBuilder.recycle();
                    stream.recycle();
                } else {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        //no-op
                    }
                }
            }
            transactionLogReplayCallback.replayComplete();
        }
    }

    class FillEntryQueueCallback implements AsyncCallbacks.ReadEntriesCallback {

        private AtomicLong outstandingReadsRequests = new AtomicLong(0);

        void fillQueue() {
            if (entryQueue.size() < entryQueue.capacity() && outstandingReadsRequests.get() == 0) {
                if (cursor.hasMoreEntries()) {
                    outstandingReadsRequests.incrementAndGet();
                    readAsync(100, this);
                }
            }
        }

        @Override
        public void readEntriesComplete(List<Entry> entries, Object ctx) {
            entryQueue.fill(new MessagePassingQueue.Supplier<Entry>() {
                private int i = 0;
                @Override
                public Entry get() {
                    Entry entry = entries.get(i);
                    i++;
                    return entry;
                }
            }, entries.size());

            outstandingReadsRequests.decrementAndGet();
        }

        @Override
        public void readEntriesFailed(ManagedLedgerException exception, Object ctx) {
            log.error("Transaction log init fail error!", exception);
            outstandingReadsRequests.decrementAndGet();
        }

    }
}