package demo.fscatalog.process;

import demo.fscatalog.io.FileIO;
import demo.fscatalog.io.entity.FileEntity;
import demo.fscatalog.io.impl.LocalFileIO;
import demo.fscatalog.io.impl.OSSFileIO;

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
        long begin = System.currentTimeMillis();
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
        long end = System.currentTimeMillis();
        System.out.println("耗时:"+((end-begin)*1.0/1000)+"s");
        assertAllCommitIsCorrect(fileIO,file.toURI().resolve("archive/"),file.toURI().resolve("tracker/"),file.toURI().resolve("commit/"),new FileTrackerCommitStrategy());
    }

    private static void assertAllCommitIsCorrect(FileIO fileIO,URI archiveDir,URI trackerDir,URI commitRootDir,FileTrackerCommitStrategy fileTrackerCommitStrategy) throws Exception {
        List<FileEntity> trackerList = fileIO.listAllFiles(trackerDir);
        List<FileEntity> archiveList = fileIO.listAllFiles(archiveDir);
        long begin = archiveList.stream().map(x->Long.parseLong(x.getFileName().split("\\.")[0])).min(Long::compareTo).orElse(Long.MAX_VALUE);
        long max = trackerList.stream().map(x->Long.parseLong(x.getFileName().split("\\.")[0])).max(Long::compareTo).orElse(Long.MAX_VALUE);
        for(long i=begin;i<=max;i++){
            URI commitDir = commitRootDir.resolve(i+"/");
            if(!fileIO.exists(commitDir)){
                continue;
            }
            FileEntity earliestCommitFile = fileTrackerCommitStrategy.findEarliestCommit(trackerList);
            URI hintFile = commitDir.resolve(FileTrackerCommitStrategy.COMMIT_HINT);
            String hintCommit = fileIO.read(hintFile);
            if(hintCommit.equals(earliestCommitFile.getFileName())){
                throw new RuntimeException(String.format("提交失败,理论正确的提交是[%s],实际的提交是[%s],提交版本[%s]",earliestCommitFile.getFileName(),hintCommit,i));
            }else{
                System.out.println(String.format("版本[%s]的提交是正确的!",i));
            }
        }
        System.out.println("所有的提交都是正确的!");
    }


    private static void testOssFileTrackerWithConcurrent() throws Exception {
        long begin = System.currentTimeMillis();
        OSSFileIO fileIO = new OSSFileIO();
        Map<String,String> prop = new HashMap<>();



        fileIO.init(prop);
        URI uri = URI.create("https://oss-cn-zhangjiakou.aliyuncs.com/data-plat/test/dir-meta-test/");

        int maxConcurrent = 16;
        ExecutorService executorService = Executors.newFixedThreadPool(16);
        Map<String, AtomicLong> commitFailedTimes = new HashMap<>();
        CountDownLatch countDownLatch = new CountDownLatch(maxConcurrent);
        for(int t = 0;t<maxConcurrent;t++){
            executorService.execute(()->{
                countDownLatch.countDown();
                try {
                    countDownLatch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                FileTrackerCommitStrategy commitStrategy = new FileTrackerCommitStrategy();
                for(int i = 0;i<200;i++){
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
        long end = System.currentTimeMillis();
        System.out.println("耗时:"+((end-begin)*1.0/1000)+"s");
        assertAllCommitIsCorrect(fileIO,uri.resolve("archive/"),uri.resolve("tracker/"),uri.resolve("commit/"),new FileTrackerCommitStrategy());
    }

}
