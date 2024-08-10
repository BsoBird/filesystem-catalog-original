package demo.fscatalog.process;

import demo.fscatalog.io.FileIO;
import demo.fscatalog.io.impl.LocalFileIO;
import demo.fscatalog.io.impl.OSSFileIO;

import java.io.File;
import java.net.URI;
import java.util.HashMap;

public class TestStrategy {
    public static void main(String[] args) throws Exception {
//        testOssFileTracker();
        testLocalFileTracker();
    }


    private static void testLocalFileTracker() throws Exception {
        FileIO fileIO = new LocalFileIO();
        fileIO.init(new HashMap<>());
        File file = new File("D:\\var\\log\\test-table");
        FileTrackerCommitStrategy fileTrackerCommitStrategy = new FileTrackerCommitStrategy();
        fileTrackerCommitStrategy.commit(fileIO,file.toURI());
    }

    private static void testOssFileTracker() throws Exception {
        FileIO fileIO = new OSSFileIO();
        fileIO.init(new HashMap<>());
        FileTrackerCommitStrategy fileTrackerCommitStrategy = new FileTrackerCommitStrategy();
        fileTrackerCommitStrategy.commit(fileIO,URI.create("https://oss-cn-zhangjiakou.aliyuncs.com/data-plat/test/dir-meta-test/"));
    }

}
