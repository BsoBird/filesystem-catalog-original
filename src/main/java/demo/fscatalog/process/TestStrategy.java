package demo.fscatalog.process;

import demo.fscatalog.io.FileIO;
import demo.fscatalog.io.entity.FileEntity;
import demo.fscatalog.io.impl.LocalFileIO;
import demo.fscatalog.io.impl.OSSFileIO;
import demo.fscatalog.io.util.UniIdUtils;

import java.io.File;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TestStrategy {
    public static void main(String[] args) throws Exception {
        testLocalFileTrackerV2();
    }

    private static void testLocalFileTrackerV2() throws Exception {
        FileIO fileIO = new LocalFileIO();
        fileIO.init(new HashMap<>());
        File file = new File("D:\\var\\log\\test-table");
        CommitStrategy commitStrategy = new FileTrackerCommitStrategyV2();
        commitStrategy.commit(fileIO,file.toURI());
    }
}
