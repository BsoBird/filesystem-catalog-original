package demo.fscatalog.process;

import demo.fscatalog.io.FileIO;
import demo.fscatalog.io.entity.Pair;
import demo.fscatalog.io.impl.LocalFileIO;
import demo.fscatalog.io.impl.OSSFileIO;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TestStrategy {
    public static void main(String[] args) throws Exception {
//        testOssFileTracker();
//        testLocalFileTracker();
//        testLocalFileTrackerWithConcurrent();
        testOssFileTrackerWithConcurrent();
//        deleteOssDir();
    }


    private static void testLocalFileTracker() throws Exception {
        FileIO fileIO = new LocalFileIO();
        fileIO.init(new HashMap<>());
        File file = new File("D:\\var\\log\\test-table");
        CommitStrategy commitStrategy = new FileTrackerCommitStrategy();
        commitStrategy.commit(fileIO,file.toURI());
    }

    private static void testOssFileTracker() throws Exception {
        FileIO fileIO = new OSSFileIO();
        fileIO.init(new HashMap<>());
        CommitStrategy commitStrategy = new FileTrackerCommitStrategy();
        commitStrategy.commit(fileIO,URI.create("https://oss-cn-zhangjiakou.aliyuncs.com/data-plat/test/dir-meta-test/"));
    }



    private static void testLocalFileTrackerWithConcurrent() throws Exception {
        FileIO fileIO = new LocalFileIO();
        fileIO.init(new HashMap<>());
        File file = new File("D:\\var\\log\\test-table");
        ExecutorService executorService = Executors.newFixedThreadPool(16);
        Map<String, AtomicLong> commitFailedTimes = new HashMap<>();
        CountDownLatch countDownLatch = new CountDownLatch(10);
        for(int t = 0;t<10;t++){
            executorService.execute(()->{
                countDownLatch.countDown();
                try {
                    countDownLatch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                CommitStrategy commitStrategy = new FileTrackerCommitStrategy();
                for(int i = 0;i<100;i++){
                    try {
                        commitStrategy.commit(fileIO,file.toURI());
                    } catch (Exception e) {
                        e.printStackTrace();
                        AtomicLong time = commitFailedTimes.computeIfAbsent(Thread.currentThread().getName(), (key)-> new AtomicLong(0));
                        time.addAndGet(1);
                    }
                }
            });
        }
        executorService.shutdown();
        executorService.awaitTermination(5, TimeUnit.MINUTES);
        System.out.println(commitFailedTimes);
    }

    private static void testOssFileTrackerWithConcurrent() throws Exception {
        OSSFileIO fileIO = new OSSFileIO();
        Map<String,String> prop = new HashMap<>();

//        String endpoint = properties.get("endpoint");
//        String accessKeyId = properties.get("accessKeyId");
//        String accessKeySecret =properties.get("accessKeySecret");
//        bucketName = properties.get("bucketName");
        fileIO.init(prop);
        URI uri = URI.create("https://oss-cn-zhangjiakou.aliyuncs.com/data-plat/test/dir-meta-test/");

        ExecutorService executorService = Executors.newFixedThreadPool(16);
        Map<String, AtomicLong> commitFailedTimes = new HashMap<>();
        CountDownLatch countDownLatch = new CountDownLatch(10);
        for(int t = 0;t<10;t++){
            executorService.execute(()->{
                countDownLatch.countDown();
                try {
                    countDownLatch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                FileTrackerCommitStrategy commitStrategy = new FileTrackerCommitStrategy();
                for(int i = 0;i<100;i++){
                    try {
                        commitStrategy.commit(fileIO,uri);
                    } catch (Exception e) {
                        if(e instanceof InterruptedIOException){
                            e.printStackTrace();
                        }
//                        e.printStackTrace();
                        AtomicLong time = commitFailedTimes.computeIfAbsent(Thread.currentThread().getName(), (key)-> new AtomicLong(0));
                        time.addAndGet(1);
                    }
                }
            });
        }
        executorService.shutdown();
        executorService.awaitTermination(5, TimeUnit.MINUTES);
        System.out.println(commitFailedTimes);
    }

}
