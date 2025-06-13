package demo.fscatalog.io.impl;

import demo.fscatalog.io.FileIO;
import demo.fscatalog.io.entity.FileEntity;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

//todo: Restrict url to FTP protocol only
public class HdfsAdapterFTPFileIO implements FileIO {
    private String userName;
    private Configuration conf;
    private FileSystem fs;
    @Override
    public synchronized void init(Map<String, String> properties) throws Exception {
        Configuration config = new Configuration();
        config.set("dfs.client.block.write.locateFollowingBlock.retries","20");
        properties.forEach(config::set);
        conf = config;
        userName = properties.get("userName");
        if(fs==null){
            fs = FileSystem.get(FileSystem.getDefaultUri(config),config,userName);
        }
    }


    @Override
    public void writeFile(URI path, String content, boolean atomicOverwrite) throws IOException {
        if(path.getPath().endsWith("/")){
            throw new UnsupportedOperationException();
        }
        if(!path.getScheme().equals("ftp")){
            throw new UnsupportedOperationException("only FTP files are supported");
        }
        try(
                FSDataOutputStream fos =  fs.create(new Path(path),atomicOverwrite)
        ){
            IOUtils.write(content, fos, StandardCharsets.UTF_8);
        }
    }

    @Override
    public List<FileEntity> listAllFiles(URI path, boolean recursion) throws IOException {
        List<FileEntity> files = new ArrayList<>();
        RemoteIterator<LocatedFileStatus> iterator =  fs.listFiles(new Path(path), recursion);
        while(iterator.hasNext()){
            LocatedFileStatus status = iterator.next();
            long modificationTime = status.getModificationTime();
            String fileName = status.getPath().getName();
            String absPath = path.getPath();
            FileEntity file = new FileEntity();
            file.setFileName(fileName);
            file.setLastModified(modificationTime);
            file.setAbsolutePath(absPath);
            files.add(file);
        }
        return files;
    }

    @Override
    public void createDirectory(URI path) throws IOException {
        if(!path.getPath().endsWith("/")){
            throw new UnsupportedOperationException();
        }
        fs.mkdirs(new Path(path));
    }

    @Override
    public void delete(URI path, boolean recursion) throws IOException {
        fs.delete(new Path(path), recursion);
    }

    @Override
    public void renameFile(URI src, URI dst, boolean overwrite) throws IOException {
        if(overwrite){
            throw new UnsupportedOperationException();
        }
        fs.rename(new Path(src),new Path(dst));
    }

    @Override
    public void close() throws IOException {
        if(fs!=null){
            fs.close();
        }
    }
}
