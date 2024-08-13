package demo.fscatalog.io;

import demo.fscatalog.io.entity.FileEntity;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public interface FileIO extends Closeable {

    void init(Map<String, String> properties);

    void writeFile(URI path,String content,boolean overwrite) throws IOException;

    void createDirectory(URI path) throws IOException;

    void delete(URI path,boolean recursion) throws IOException;

    default boolean exists(URI path) throws IOException{
        throw new UnsupportedOperationException();
    }

    default String read(URI path) throws IOException{
        throw new UnsupportedOperationException();
    }

    default boolean lock(String lockInfo,long timeout, TimeUnit unit) throws IOException {
        // WE CAN IMPLEMENT A FILE BASED LOCK.
        throw new UnsupportedOperationException();
    }
    default void unlock(){
        // WE CAN IMPLEMENT A FILE BASED LOCK.
        throw new UnsupportedOperationException();
    }

    /**
     * Please make sure that there are no subfolders under path, otherwise different file systems may produce different results.
     * @param path
     * @return
     * @throws IOException
     */
    default List<FileEntity> listAllFiles(URI path) throws IOException{
        throw new UnsupportedOperationException();
    }

    default long getFileSystemTimeAccuracy(){
        return 1000L;
    }

    default void renameFile(URI src, URI dst,boolean overwrite) throws IOException{
        throw new UnsupportedOperationException();
    }
}
