package demo.fscatalog.io;

import demo.fscatalog.io.entity.FileEntity;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


public interface FileIO extends Closeable {

    // Thinking:
    // For file system related operations,
    // different operations should probably be decentralized to different interfaces,
    // through a combination of interfaces to achieve functionality
    // example: renameAbleInterface, appendAbleInterface.......
    // public HdfsFileIO implements FileIO,renameAbleInterface,appendAbleInterface{xxx}


    void init(Map<String, String> properties) throws Exception;

    /**
     * Only write a single file.
     * <p>
     * In addition to this,
     * there is no guarantee that the behavior of multiple file systems will be consistent.
     * For example, HDFS does not support atomicOverwrite, S3 can only atomicOverwrite.
     * <p>
     * If a fileIo implementation does not work with the current default settings,
     * the user should reimplement the method.
     */
    default void writeFileWithNoBehaviourPromises(URI path,String content) throws IOException{
        writeFile(path,content,false);
    }

    void writeFile(URI path,String content,boolean atomicOverwrite) throws IOException;

    void createDirectory(URI path) throws IOException;

    void delete(URI path,boolean recursion) throws IOException;

    default boolean exists(URI path) throws IOException{
        throw new UnsupportedOperationException();
    }

    default String read(URI path) throws IOException{
        throw new UnsupportedOperationException();
    }

    @Deprecated
    default boolean lock(String lockInfo,long timeout, TimeUnit unit) throws IOException {
        //TODO: WE CAN IMPLEMENT A FILE BASED LOGIC LOCK.
        throw new UnsupportedOperationException();
    }

    @Deprecated
    default void unlock(){
        //TODO: WE CAN IMPLEMENT A FILE BASED LOGIC LOCK.
        throw new UnsupportedOperationException();
    }

    @Deprecated
    default void appendWrite(URI file,String content){
        throw new UnsupportedOperationException();
    }

    /**
     * Please make sure that there are no subfolders under path, otherwise different file systems may produce different results.
     * @param path
     * @return
     * @throws IOException
     */
    @Deprecated
    default List<FileEntity> listAllFiles(URI path) throws IOException{
        throw new UnsupportedOperationException();
    }

    default List<FileEntity> listAllFiles(URI path,boolean recursion) throws IOException{
        throw new UnsupportedOperationException();
    }

    @Deprecated
    default long getFileSystemTimeAccuracy(){
        // Maybe it's not useful. We should delete it.
        return 1000L;
    }

    default void renameFile(URI src, URI dst,boolean overwrite) throws IOException{
        throw new UnsupportedOperationException();
    }
}
