/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.openmessaging.storage.dleger.store.file;

import io.openmessaging.storage.dleger.DLegerConfig;
import io.openmessaging.storage.dleger.MemberState;
import io.openmessaging.storage.dleger.ShutdownAbleThread;
import io.openmessaging.storage.dleger.entry.DLegerEntry;
import io.openmessaging.storage.dleger.entry.DLegerEntryCoder;
import io.openmessaging.storage.dleger.protocol.DLegerResponseCode;
import io.openmessaging.storage.dleger.store.DLegerStore;
import io.openmessaging.storage.dleger.utils.PreConditions;
import io.openmessaging.storage.dleger.utils.UtilAll;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DLegerMmapFileStore extends DLegerStore {

    public static final int MAGIC_1 = 1;
    public static final int CURRENT_MAGIC = MAGIC_1;
    public static final int INDEX_NUIT_SIZE = 32;
    private static Logger logger = LoggerFactory.getLogger(DLegerMmapFileStore.class);
    public List<AppendHook> appendHooks = new ArrayList<>();
    private long legerBeginIndex = -1;
    private long legerEndIndex = -1;
    private long committedIndex = -1;
    private long committedPos = -1;
    private long legerEndTerm;
    private DLegerConfig dLegerConfig;
    private MemberState memberState;
    private MmapFileList dataFileList;
    private MmapFileList indexFileList;
    private ThreadLocal<ByteBuffer> localEntryBuffer;
    private ThreadLocal<ByteBuffer> localIndexBuffer;
    private FlushDataService flushDataService;
    private CleanSpaceService cleanSpaceService;
    private boolean isDiskFull = false;

    public DLegerMmapFileStore(DLegerConfig dLegerConfig, MemberState memberState) {
        this.dLegerConfig = dLegerConfig;
        this.memberState = memberState;
        this.dataFileList = new MmapFileList(dLegerConfig.getDataStorePath(), dLegerConfig.getMappedFileSizeForEntryData());
        this.indexFileList = new MmapFileList(dLegerConfig.getIndexStorePath(), dLegerConfig.getMappedFileSizeForEntryIndex());
        localEntryBuffer = ThreadLocal.withInitial(() -> ByteBuffer.allocate(4 * 1024 * 1024));
        localIndexBuffer = ThreadLocal.withInitial(() -> ByteBuffer.allocate(INDEX_NUIT_SIZE * 2));
        flushDataService = new FlushDataService("DLegerFlushDataService", logger);
        cleanSpaceService = new CleanSpaceService("DLegerCleanSpaceService", logger);
    }

    public void startup() {
        this.dataFileList.load();
        this.indexFileList.load();
        recover();
        flushDataService.start();
        cleanSpaceService.start();
    }

    public void shutdown() {
        this.dataFileList.flush(0);
        this.indexFileList.flush(0);
        cleanSpaceService.shutdown();
        flushDataService.shutdown();
    }

    public long getWritePos() {
        return dataFileList.getMaxWrotePosition();
    }

    public long getFlushPos() {
        return dataFileList.getFlushedWhere();
    }

    public void flush() {
        this.dataFileList.flush(0);
        this.indexFileList.flush(0);
    }

    public void recover() {
        PreConditions.check(dataFileList.checkSelf(), DLegerResponseCode.DISK_ERROR, "check data file order failed before recovery");
        PreConditions.check(indexFileList.checkSelf(), DLegerResponseCode.DISK_ERROR, "check index file order failed before recovery");
        final List<MmapFile> mappedFiles = this.dataFileList.getMappedFiles();
        if (mappedFiles.isEmpty()) {
            this.indexFileList.updateWherePosition(0);
            this.indexFileList.truncateOffset(0);
            return;
        }
        MmapFile lastMappedFile = dataFileList.getLastMappedFile();
        int index = mappedFiles.size() - 3;
        if (index < 0) {
            index = 0;
        }

        long firstEntryIndex = -1;
        for (int i = index; i >= 0; i--) {
            index = i;
            MmapFile mappedFile = mappedFiles.get(index);
            ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();
            try {
                long startPos = mappedFile.getFileFromOffset();
                int magic = byteBuffer.getInt();
                int size = byteBuffer.getInt();
                long entryIndex = byteBuffer.getLong();
                long entryTerm = byteBuffer.getLong();
                long pos = byteBuffer.getLong();
                byteBuffer.getInt(); //channel
                byteBuffer.getInt(); //chain crc
                byteBuffer.getInt(); //body crc
                int bodySize = byteBuffer.getInt();
                PreConditions.check(magic != MmapFileList.BLANK_MAGIC_CODE && magic >= MAGIC_1 && MAGIC_1 <= CURRENT_MAGIC, DLegerResponseCode.DISK_ERROR, "unknown magic=%d", magic);
                PreConditions.check(size > DLegerEntry.HEADER_SIZE, DLegerResponseCode.DISK_ERROR, "Size %d should > %d", size, DLegerEntry.HEADER_SIZE);

                PreConditions.check(pos == startPos, DLegerResponseCode.DISK_ERROR, "pos %d != %d", pos, startPos);
                PreConditions.check(bodySize + DLegerEntry.BODY_OFFSET == size, DLegerResponseCode.DISK_ERROR, "size %d != %d + %d", size, bodySize, DLegerEntry.BODY_OFFSET);

                SelectMmapBufferResult indexSbr = indexFileList.getData(entryIndex * INDEX_NUIT_SIZE);
                PreConditions.check(indexSbr != null, DLegerResponseCode.DISK_ERROR, "index=%d pos=%d", entryIndex, entryIndex * INDEX_NUIT_SIZE);
                indexSbr.release();
                ByteBuffer indexByteBuffer = indexSbr.getByteBuffer();
                int magicFromIndex = indexByteBuffer.getInt();
                long posFromIndex = indexByteBuffer.getLong();
                int sizeFromIndex = indexByteBuffer.getInt();
                long indexFromIndex = indexByteBuffer.getLong();
                long termFromIndex = indexByteBuffer.getLong();
                PreConditions.check(magic == magicFromIndex, DLegerResponseCode.DISK_ERROR, "magic %d != %d", magic, magicFromIndex);
                PreConditions.check(size == sizeFromIndex, DLegerResponseCode.DISK_ERROR, "size %d != %d", size, sizeFromIndex);
                PreConditions.check(entryIndex == indexFromIndex, DLegerResponseCode.DISK_ERROR, "index %d != %d", entryIndex, indexFromIndex);
                PreConditions.check(entryTerm == termFromIndex, DLegerResponseCode.DISK_ERROR, "term %d != %d", entryTerm, termFromIndex);
                PreConditions.check(posFromIndex == mappedFile.getFileFromOffset(), DLegerResponseCode.DISK_ERROR, "pos %d != %d", mappedFile.getFileFromOffset(), posFromIndex);
                firstEntryIndex = entryIndex;
                break;
            } catch (Throwable t) {
                logger.warn("Pre check data and index failed {}", mappedFile.getFileName(), t);
            }
        }

        MmapFile mappedFile = mappedFiles.get(index);
        ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();
        logger.info("Begin to recover data from entryIndex={} fileIndex={} fileSize={} fileName={} ", firstEntryIndex, index, mappedFiles.size(), mappedFile.getFileName());
        long lastEntryIndex = -1;
        long lastEntryTerm = -1;
        long processOffset = mappedFile.getFileFromOffset();
        boolean needWriteIndex = false;
        while (true) {
            try {
                int relativePos = byteBuffer.position();
                long absolutePos = mappedFile.getFileFromOffset() + relativePos;
                int magic = byteBuffer.getInt();
                if (magic == MmapFileList.BLANK_MAGIC_CODE) {
                    processOffset = mappedFile.getFileFromOffset() + mappedFile.getFileSize();
                    index++;
                    if (index >= mappedFiles.size()) {
                        logger.info("Recover data file over, the last file {}", mappedFile.getFileName());
                        break;
                    } else {
                        mappedFile = mappedFiles.get(index);
                        byteBuffer = mappedFile.sliceByteBuffer();
                        processOffset = mappedFile.getFileFromOffset();
                        logger.info("Trying to recover data file {}", mappedFile.getFileName());
                        continue;
                    }
                }

                int size = byteBuffer.getInt();
                long entryIndex = byteBuffer.getLong();
                long entryTerm = byteBuffer.getLong();
                long pos = byteBuffer.getLong();
                byteBuffer.getInt(); //channel
                byteBuffer.getInt(); //chain crc
                byteBuffer.getInt(); //body crc
                int bodySize = byteBuffer.getInt();

                PreConditions.check(pos == absolutePos, DLegerResponseCode.DISK_ERROR, "pos %d != %d", pos, absolutePos);
                PreConditions.check(bodySize + DLegerEntry.BODY_OFFSET == size, DLegerResponseCode.DISK_ERROR, "size %d != %d + %d", size, bodySize, DLegerEntry.BODY_OFFSET);

                byteBuffer.position(relativePos + size);

                PreConditions.check(magic <= CURRENT_MAGIC && magic >= MAGIC_1, DLegerResponseCode.DISK_ERROR, "pos=%d size=%d magic=%d index=%d term=%d currMagic=%d", absolutePos, size, magic, entryIndex, entryTerm, CURRENT_MAGIC);
                if (lastEntryIndex != -1) {
                    PreConditions.check(entryIndex == lastEntryIndex + 1, DLegerResponseCode.DISK_ERROR, "pos=%d size=%d magic=%d index=%d term=%d lastEntryIndex=%d", absolutePos, size, magic, entryIndex, entryTerm, lastEntryIndex);
                }
                PreConditions.check(entryTerm >= lastEntryTerm, DLegerResponseCode.DISK_ERROR, "pos=%d size=%d magic=%d index=%d term=%d lastEntryTerm=%d ", absolutePos, size, magic, entryIndex, entryTerm, lastEntryTerm);
                PreConditions.check(size > DLegerEntry.HEADER_SIZE, DLegerResponseCode.DISK_ERROR, "size %d should > %d", size, DLegerEntry.HEADER_SIZE);
                if (!needWriteIndex) {
                    try {
                        SelectMmapBufferResult indexSbr = indexFileList.getData(entryIndex * INDEX_NUIT_SIZE);
                        PreConditions.check(indexSbr != null, DLegerResponseCode.DISK_ERROR, "index=%d pos=%d", entryIndex, entryIndex * INDEX_NUIT_SIZE);
                        indexSbr.release();
                        ByteBuffer indexByteBuffer = indexSbr.getByteBuffer();
                        int magicFromIndex = indexByteBuffer.getInt();
                        long posFromIndex = indexByteBuffer.getLong();
                        int sizeFromIndex = indexByteBuffer.getInt();
                        long indexFromIndex = indexByteBuffer.getLong();
                        long termFromIndex = indexByteBuffer.getLong();
                        PreConditions.check(magic == magicFromIndex, DLegerResponseCode.DISK_ERROR, "magic %d != %d", magic, magicFromIndex);
                        PreConditions.check(size == sizeFromIndex, DLegerResponseCode.DISK_ERROR, "size %d != %d", size, sizeFromIndex);
                        PreConditions.check(entryIndex == indexFromIndex, DLegerResponseCode.DISK_ERROR, "index %d != %d", entryIndex, indexFromIndex);
                        PreConditions.check(entryTerm == termFromIndex, DLegerResponseCode.DISK_ERROR, "term %d != %d", entryTerm, termFromIndex);
                        PreConditions.check(absolutePos == posFromIndex, DLegerResponseCode.DISK_ERROR, "pos %d != %d", mappedFile.getFileFromOffset(), posFromIndex);
                    } catch (Throwable t) {
                        logger.warn("Compare data to index failed {}", mappedFile.getFileName(), t);
                        indexFileList.truncateOffset(entryIndex * INDEX_NUIT_SIZE);
                        if (indexFileList.getMaxWrotePosition() != entryIndex * INDEX_NUIT_SIZE) {
                            long truncateIndexOffset = entryIndex * INDEX_NUIT_SIZE;
                            logger.warn("[Recovery] rebuild for index wrotePos={} not equal to truncatePos={}", indexFileList.getMaxWrotePosition(), truncateIndexOffset);
                            PreConditions.check(indexFileList.rebuildWithPos(truncateIndexOffset), DLegerResponseCode.DISK_ERROR, "rebuild index truncatePos=%d", truncateIndexOffset);
                        }
                        needWriteIndex = true;
                    }
                }
                if (needWriteIndex) {
                    ByteBuffer indexBuffer = localIndexBuffer.get();
                    DLegerEntryCoder.encodeIndex(absolutePos, size, magic, entryIndex, entryTerm, indexBuffer);
                    long indexPos = indexFileList.append(indexBuffer.array(), 0, indexBuffer.remaining(), false);
                    PreConditions.check(indexPos == entryIndex * INDEX_NUIT_SIZE, DLegerResponseCode.DISK_ERROR, "Write index failed index=%d", entryIndex);
                }
                lastEntryIndex = entryIndex;
                lastEntryTerm = entryTerm;
                processOffset += size;
            } catch (Throwable t) {
                logger.info("Recover data file to the end of {} ", mappedFile.getFileName(), t);
                break;
            }
        }
        logger.info("Recover data to the end entryIndex={} processOffset={} lastFileOffset={} cha={}",
            lastEntryIndex, processOffset, lastMappedFile.getFileFromOffset(), processOffset - lastMappedFile.getFileFromOffset());
        if (lastMappedFile.getFileFromOffset() - processOffset > lastMappedFile.getFileSize()) {
            logger.error("[MONITOR]The processOffset is too small, you should check it manually before truncating the data from {}", processOffset);
            System.exit(-1);
        }

        legerEndIndex = lastEntryIndex;
        legerEndTerm = lastEntryTerm;
        if (lastEntryIndex != -1) {
            DLegerEntry entry = get(lastEntryIndex);
            PreConditions.check(entry != null, DLegerResponseCode.DISK_ERROR, "recheck get null entry");
            PreConditions.check(entry.getIndex() == lastEntryIndex, DLegerResponseCode.DISK_ERROR, "recheck index %d != %d", entry.getIndex(), lastEntryIndex);
            reviseLegerBeginIndex();
        } else {
            processOffset = 0;
        }
        this.dataFileList.updateWherePosition(processOffset);
        this.dataFileList.truncateOffset(processOffset);
        long indexProcessOffset = (lastEntryIndex + 1) * INDEX_NUIT_SIZE;
        this.indexFileList.updateWherePosition(indexProcessOffset);
        this.indexFileList.truncateOffset(indexProcessOffset);
        updateLegerEndIndexAndTerm();
        PreConditions.check(dataFileList.checkSelf(), DLegerResponseCode.DISK_ERROR, "check data file order failed after recovery");
        PreConditions.check(indexFileList.checkSelf(), DLegerResponseCode.DISK_ERROR, "check index file order failed after recovery");
        return;
    }

    private void reviseLegerBeginIndex() {
        //get leger begin index
        MmapFile firstFile = dataFileList.getFirstMappedFile();
        SelectMmapBufferResult sbr = firstFile.selectMappedBuffer(0);
        try {
            ByteBuffer tmpBuffer = sbr.getByteBuffer();
            tmpBuffer.position(firstFile.getStartPosition());
            tmpBuffer.getInt(); //magic
            tmpBuffer.getInt(); //size
            legerBeginIndex = tmpBuffer.getLong();
            indexFileList.resetOffset(legerBeginIndex * INDEX_NUIT_SIZE);
        } finally {
            SelectMmapBufferResult.release(sbr);
        }

    }

    @Override
    public DLegerEntry appendAsLeader(DLegerEntry entry) {
        PreConditions.check(memberState.isLeader(), DLegerResponseCode.NOT_LEADER);
        PreConditions.check(!isDiskFull, DLegerResponseCode.DISK_FULL);
        ByteBuffer dataBuffer = localEntryBuffer.get();
        ByteBuffer indexBuffer = localIndexBuffer.get();
        DLegerEntryCoder.encode(entry, dataBuffer);
        int entrySize = dataBuffer.remaining();
        synchronized (memberState) {
            PreConditions.check(memberState.isLeader(), DLegerResponseCode.NOT_LEADER, null);
            long nextIndex = legerEndIndex + 1;
            entry.setIndex(nextIndex);
            entry.setTerm(memberState.currTerm());
            entry.setMagic(CURRENT_MAGIC);
            DLegerEntryCoder.setIndexTerm(dataBuffer, nextIndex, memberState.currTerm(), CURRENT_MAGIC);
            long prePos = dataFileList.preAppend(dataBuffer.remaining());
            entry.setPos(prePos);
            PreConditions.check(prePos != -1, DLegerResponseCode.DISK_ERROR, null);
            DLegerEntryCoder.setPos(dataBuffer, prePos);
            for (AppendHook writeHook : appendHooks) {
                writeHook.doHook(entry, dataBuffer.slice(), DLegerEntry.BODY_OFFSET);
            }
            long dataPos = dataFileList.append(dataBuffer.array(), 0, dataBuffer.remaining());
            PreConditions.check(dataPos != -1, DLegerResponseCode.DISK_ERROR, null);
            PreConditions.check(dataPos == prePos, DLegerResponseCode.DISK_ERROR, null);
            DLegerEntryCoder.encodeIndex(dataPos, entrySize, CURRENT_MAGIC, nextIndex, memberState.currTerm(), indexBuffer);
            long indexPos = indexFileList.append(indexBuffer.array(), 0, indexBuffer.remaining(), false);
            PreConditions.check(indexPos == entry.getIndex() * INDEX_NUIT_SIZE, DLegerResponseCode.DISK_ERROR, null);
            if (logger.isDebugEnabled()) {
                logger.info("[{}] Append as Leader {} {}", memberState.getSelfId(), entry.getIndex(), entry.getBody().length);
            }
            legerEndIndex++;
            legerEndTerm = memberState.currTerm();
            if (legerBeginIndex == -1) {
                legerBeginIndex = legerEndIndex;
            }
            updateLegerEndIndexAndTerm();
            return entry;
        }
    }

    @Override
    public long truncate(DLegerEntry entry, long leaderTerm, String leaderId) {
        PreConditions.check(memberState.isFollower(), DLegerResponseCode.NOT_FOLLOWER, null);
        ByteBuffer dataBuffer = localEntryBuffer.get();
        ByteBuffer indexBuffer = localIndexBuffer.get();
        DLegerEntryCoder.encode(entry, dataBuffer);
        int entrySize = dataBuffer.remaining();
        synchronized (memberState) {
            PreConditions.check(memberState.isFollower(), DLegerResponseCode.NOT_FOLLOWER, "role=%s", memberState.getRole());
            PreConditions.check(leaderTerm == memberState.currTerm(), DLegerResponseCode.INCONSISTENT_TERM, "term %d != %d", leaderTerm, memberState.currTerm());
            PreConditions.check(leaderId.equals(memberState.getLeaderId()), DLegerResponseCode.INCONSISTENT_LEADER, "leaderId %s != %s", leaderId, memberState.getLeaderId());
            boolean existedEntry;
            try {
                DLegerEntry tmp = get(entry.getIndex());
                existedEntry = entry.equals(tmp);
            } catch (Throwable ignored) {
                existedEntry = false;
            }
            long truncatePos = existedEntry ? entry.getPos() + entry.getSize() : entry.getPos();
            dataFileList.truncateOffset(truncatePos);
            if (dataFileList.getMaxWrotePosition() != truncatePos) {
                logger.warn("[TRUNCATE] rebuild for data wrotePos: {} != truncatePos: {}", dataFileList.getMaxWrotePosition(), truncatePos);
                PreConditions.check(dataFileList.rebuildWithPos(truncatePos), DLegerResponseCode.DISK_ERROR, "rebuild data truncatePos=%d", truncatePos);
            }
            if (!existedEntry) {
                long dataPos = dataFileList.append(dataBuffer.array(), 0, dataBuffer.remaining());
                PreConditions.check(dataPos == entry.getPos(), DLegerResponseCode.DISK_ERROR, " %d != %d", dataPos, entry.getPos());
            }

            long truncateIndexOffset = entry.getIndex() * INDEX_NUIT_SIZE;
            indexFileList.truncateOffset(truncateIndexOffset);
            if (indexFileList.getMaxWrotePosition() != truncateIndexOffset) {
                logger.warn("[TRUNCATE] rebuild for index wrotePos: {} != truncatePos: {}", indexFileList.getMaxWrotePosition(), truncateIndexOffset);
                PreConditions.check(indexFileList.rebuildWithPos(truncateIndexOffset), DLegerResponseCode.DISK_ERROR, "rebuild index truncatePos=%d", truncateIndexOffset);
            }
            DLegerEntryCoder.encodeIndex(entry.getPos(), entrySize, entry.getMagic(), entry.getIndex(), entry.getTerm(), indexBuffer);
            long indexPos = indexFileList.append(indexBuffer.array(), 0, indexBuffer.remaining(), false);
            PreConditions.check(indexPos == entry.getIndex() * INDEX_NUIT_SIZE, DLegerResponseCode.DISK_ERROR, null);
            legerEndTerm = memberState.currTerm();
            legerEndIndex = entry.getIndex();
            reviseLegerBeginIndex();
            updateLegerEndIndexAndTerm();
            return entry.getIndex();
        }
    }

    @Override
    public DLegerEntry appendAsFollower(DLegerEntry entry, long leaderTerm, String leaderId) {
        PreConditions.check(memberState.isFollower(), DLegerResponseCode.NOT_FOLLOWER, "role=%s", memberState.getRole());
        PreConditions.check(!isDiskFull, DLegerResponseCode.DISK_FULL);
        ByteBuffer dataBuffer = localEntryBuffer.get();
        ByteBuffer indexBuffer = localIndexBuffer.get();
        DLegerEntryCoder.encode(entry, dataBuffer);
        int entrySize = dataBuffer.remaining();
        synchronized (memberState) {
            PreConditions.check(memberState.isFollower(), DLegerResponseCode.NOT_FOLLOWER, "role=%s", memberState.getRole());
            long nextIndex = legerEndIndex + 1;
            PreConditions.check(nextIndex == entry.getIndex(), DLegerResponseCode.INCONSISTENT_INDEX, null);
            PreConditions.check(leaderTerm == memberState.currTerm(), DLegerResponseCode.INCONSISTENT_TERM, null);
            PreConditions.check(leaderId.equals(memberState.getLeaderId()), DLegerResponseCode.INCONSISTENT_LEADER, null);
            long dataPos = dataFileList.append(dataBuffer.array(), 0, dataBuffer.remaining());
            PreConditions.check(dataPos == entry.getPos(), DLegerResponseCode.DISK_ERROR, "%d != %d", dataPos, entry.getPos());
            DLegerEntryCoder.encodeIndex(dataPos, entrySize, entry.getMagic(), entry.getIndex(), entry.getTerm(), indexBuffer);
            long indexPos = indexFileList.append(indexBuffer.array(), 0, indexBuffer.remaining(), false);
            PreConditions.check(indexPos == entry.getIndex() * INDEX_NUIT_SIZE, DLegerResponseCode.DISK_ERROR, null);
            legerEndTerm = memberState.currTerm();
            legerEndIndex = entry.getIndex();
            if (legerBeginIndex == -1) {
                legerBeginIndex = legerEndIndex;
            }
            updateLegerEndIndexAndTerm();
            return entry;
        }

    }

    @Override
    public long getLegerEndIndex() {
        return legerEndIndex;
    }

    @Override public long getLegerBeginIndex() {
        return legerBeginIndex;
    }

    @Override
    public DLegerEntry get(Long index) {
        PreConditions.check(index >= 0, DLegerResponseCode.INDEX_OUT_OF_RANGE, "%d should gt 0", index);
        PreConditions.check(index <= legerEndIndex && index >= legerBeginIndex, DLegerResponseCode.INDEX_OUT_OF_RANGE, "%d should between %d-%d", index, legerBeginIndex, legerEndIndex);
        SelectMmapBufferResult indexSbr = null;
        SelectMmapBufferResult dataSbr = null;
        try {
            indexSbr = indexFileList.getData(index * INDEX_NUIT_SIZE, INDEX_NUIT_SIZE);
            PreConditions.check(indexSbr != null && indexSbr.getByteBuffer() != null, DLegerResponseCode.DISK_ERROR, "Get null index for %d", index);
            indexSbr.getByteBuffer().getInt(); //magic
            long pos = indexSbr.getByteBuffer().getLong();
            int size = indexSbr.getByteBuffer().getInt();
            dataSbr = dataFileList.getData(pos, size);
            PreConditions.check(dataSbr != null && dataSbr.getByteBuffer() != null, DLegerResponseCode.DISK_ERROR, "Get null data for %d", index);
            DLegerEntry dLegerEntry = DLegerEntryCoder.decode(dataSbr.getByteBuffer());
            PreConditions.check(pos == dLegerEntry.getPos(), DLegerResponseCode.DISK_ERROR, "%d != %d", pos, dLegerEntry.getPos());
            return dLegerEntry;
        } finally {
            SelectMmapBufferResult.release(indexSbr);
            SelectMmapBufferResult.release(dataSbr);
        }
    }

    @Override
    public long getCommittedIndex() {
        return committedIndex;
    }

    public void updateCommittedIndex(long term, long committedIndex) {
        if (committedIndex == -1) {
            return;
        }
        if (term < memberState.currTerm() || committedIndex < this.committedIndex) {
            logger.debug("[MONITOR]Skip update committed index for term {} < {} or index {} < {}", term, memberState.currTerm(), committedIndex, this.committedIndex);
            return;
        }
        long endIndex = legerEndIndex;
        if (committedIndex > endIndex) {
            committedIndex = endIndex;
        }
        DLegerEntry dLegerEntry = get(committedIndex);
        PreConditions.check(dLegerEntry != null, DLegerResponseCode.DISK_ERROR);
        this.committedIndex = committedIndex;
        this.committedPos = dLegerEntry.getPos() + dLegerEntry.getSize();
    }

    @Override
    public long getLegerEndTerm() {
        return legerEndTerm;
    }

    public long getCommittedPos() {
        return committedPos;
    }

    public void addAppendHook(AppendHook writeHook) {
        if (!appendHooks.contains(writeHook)) {
            appendHooks.add(writeHook);
        }
    }

    @Override
    public MemberState getMemberState() {
        return memberState;
    }

    public MmapFileList getDataFileList() {
        return dataFileList;
    }

    public MmapFileList getIndexFileList() {
        return indexFileList;
    }

    public interface AppendHook {
        void doHook(DLegerEntry entry, ByteBuffer buffer, int bodyOffset);
    }

    class FlushDataService extends ShutdownAbleThread {

        public FlushDataService(String name, Logger logger) {
            super(name, logger);
        }

        @Override public void doWork() {
            try {
                long start = System.currentTimeMillis();
                DLegerMmapFileStore.this.dataFileList.flush(0);
                DLegerMmapFileStore.this.indexFileList.flush(0);
                if (UtilAll.elapsed(start) > 500) {
                    logger.info("Flush data cost={} ms", UtilAll.elapsed(start));
                }

                waitForRunning(dLegerConfig.getFlushFileInterval());
            } catch (Throwable t) {
                logger.info("Error in {}", getName(), t);
                UtilAll.sleep(200);
            }
        }
    }

    class CleanSpaceService extends ShutdownAbleThread {

        double storeBaseRatio = UtilAll.getDiskPartitionSpaceUsedPercent(dLegerConfig.getStoreBaseDir());
        double dataRatio = UtilAll.getDiskPartitionSpaceUsedPercent(dLegerConfig.getDataStorePath());

        public CleanSpaceService(String name, Logger logger) {
            super(name, logger);
        }

        @Override public void doWork() {
            try {
                storeBaseRatio = UtilAll.getDiskPartitionSpaceUsedPercent(dLegerConfig.getStoreBaseDir());
                dataRatio = UtilAll.getDiskPartitionSpaceUsedPercent(dLegerConfig.getDataStorePath());
                long fileReservedTimeMs = dLegerConfig.getFileReservedHours() * 3600 * 1000;
                //If the disk is full, should prevent more data to get in
                DLegerMmapFileStore.this.isDiskFull = isNeedForbiddenWrite();
                boolean timeUp = isTimeToDelete();
                boolean checkExpired = isNeedCheckExpired();
                boolean forceClean = isNeedForceClean();
                boolean enableForceClean = dLegerConfig.isEnableDiskForceClean();
                if (timeUp || checkExpired) {
                    int count = getDataFileList().deleteExpiredFileByTime(fileReservedTimeMs, 100, 120 * 1000, forceClean && enableForceClean);
                    if (count > 0 || (forceClean && enableForceClean) || isDiskFull) {
                        logger.info("Clean space count={} timeUp={} checkExpired={} forceClean={} enableForceClean={} diskFull={} storeBaseRatio={} dataRatio={}",
                            count, timeUp, checkExpired, forceClean, enableForceClean, isDiskFull, storeBaseRatio, dataRatio);
                    }
                    if (count > 0) {
                        DLegerMmapFileStore.this.reviseLegerBeginIndex();
                    }
                }
                waitForRunning(100);
            } catch (Throwable t) {
                logger.info("Error in {}", getName(), t);
                UtilAll.sleep(200);
            }
        }

        private boolean isTimeToDelete() {
            String when = DLegerMmapFileStore.this.dLegerConfig.getDeleteWhen();
            if (UtilAll.isItTimeToDo(when)) {
                return true;
            }

            return false;
        }

        private boolean isNeedCheckExpired() {
            if (storeBaseRatio > dLegerConfig.getDiskSpaceRatioToCheckExpired()
                || dataRatio > dLegerConfig.getDiskSpaceRatioToCheckExpired()) {
                return true;
            }
            return false;
        }

        private boolean isNeedForceClean() {
            if (storeBaseRatio > dLegerConfig.getDiskSpaceRatioToForceClean()
                || dataRatio > dLegerConfig.getDiskSpaceRatioToForceClean()) {
                return true;
            }
            return false;
        }

        private boolean isNeedForbiddenWrite() {
            if (storeBaseRatio > dLegerConfig.getDiskFullRatio()
                || dataRatio > dLegerConfig.getDiskFullRatio()) {
                return true;
            }
            return false;
        }
    }
}
