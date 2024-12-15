package demo.fscatalog.io.impl;

import demo.fscatalog.io.FileIO;
import demo.fscatalog.io.entity.FileEntity;
import demo.fscatalog.io.util.UniIdUtils;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


public class LocalFileIO implements FileIO {
    private String OS = null;
    @Override
    public void init(Map<String, String> properties) {
        OS = System.getProperty("os.name").toLowerCase();
    }

    @Override
    public boolean exists(URI path) throws IOException {
        File file = new File(path.getPath());
        return file.exists();
    }

    @Override
    public String read(URI path) throws IOException {
        File file = new File(path.getPath());
        StringBuilder sb = new StringBuilder();
        String line = null;
        try(FileReader reader = new FileReader(file);
            BufferedReader bufferedReader = new BufferedReader(reader)){
            while((line = bufferedReader.readLine())!=null){
                sb.append(line);
            }
        }
        return sb.toString();
    }

    @Override
    public void writeFile(URI path, String content, boolean atomicOverwrite) throws IOException{
        File file = new File(path);
        if(file.isDirectory()){
            throw new IllegalArgumentException("can not write to a directory");
        }
        file.getParentFile().mkdirs();
        String uuid = UniIdUtils.getUniId(UniIdUtils.SNOW_FLAKE);
        File tempFile = File.createTempFile(uuid,"");
        try(FileWriter writer = new FileWriter(tempFile)){
            writer.write(content);
            writer.flush();
        }
        if(OS.contains("windows")){
            if(atomicOverwrite){
                String fileName = tempFile.getName();
                File sameRootTmpFile = new File(file.getParentFile().toURI().resolve(fileName));
                try{
                    //In windows operating system, atomic renaming can only work under the same disk drive, that is, under the same disk letter.
                    if(!tempFile.renameTo(sameRootTmpFile)){
                        throw new FileAlreadyExistsException("Already exists :"+sameRootTmpFile.getAbsolutePath());
                    }
                    Files.move(sameRootTmpFile.toPath(),file.toPath(), StandardCopyOption.ATOMIC_MOVE);
                }finally {
                    Files.deleteIfExists(sameRootTmpFile.toPath());
                }
            }else{
                if(!tempFile.renameTo(file)){
                    throw new FileAlreadyExistsException("Already exists :"+file.getAbsolutePath());
                }
            }
        }else{
            CopyOption copyOption = atomicOverwrite?StandardCopyOption.REPLACE_EXISTING:StandardCopyOption.ATOMIC_MOVE;
            Files.move(tempFile.toPath(),file.toPath(), copyOption);
        }
    }


    @Override
    public void createDirectory(URI path) {
        File file = new File(path);
        file.mkdirs();
    }

    @Override
    public void delete(URI path,boolean recursion) throws IOException {
        File file = new File(path);
        if(recursion){
            List<Path> walkResult = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(file.toPath())) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(walkResult::add);
            }catch (NoSuchFileException e){
                //do-nothing
                e.printStackTrace();
            }
            for (Path deletePath : walkResult) {
                Files.delete(deletePath);
            }
        }else{
            file.delete();
        }
    }

    @Override
    public void close() throws IOException {
        //do-nothing
    }

    @Override
    public List<FileEntity> listAllFiles(URI path,boolean recursion) throws IOException {
        if(recursion){
            return getAllFilesWithRecursion(path);
        }else{
            return getAllFilesWithOutRecursion(path);
        }
    }

    private List<FileEntity> getAllFilesWithRecursion(URI path) throws IOException {
        List<FileEntity> fileList = new ArrayList<>();
        List<Path> walkResult = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(new File(path).toPath())) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(walkResult::add);
        }catch (NoSuchFileException e){
            //do-nothing
        }
        for (Path path1 : walkResult) {
            File file1 = path1.toFile();
            if(file1.isFile()){
                String name = file1.getName();
                long lastModified = file1.lastModified();
                FileEntity entity = new FileEntity();
                entity.setFileName(name);
                entity.setLastModified(lastModified);
                entity.setAbsolutePath(file1.getAbsolutePath());
                fileList.add(entity);
            }
        }
        return fileList;
    }

    private List<FileEntity> getAllFilesWithOutRecursion(URI path){
        List<FileEntity> fileList = new ArrayList<>();
        File file = new File(path);
        File [] files = file.listFiles();
        if(files == null){
            files = new File[0];
        }
        for (File file1 : files) {
            String name = file1.getName();
            long lastModified = file1.lastModified();
            FileEntity entity = new FileEntity();
            entity.setFileName(name);
            entity.setLastModified(lastModified);
            entity.setAbsolutePath(file1.getAbsolutePath());
            fileList.add(entity);
        }
        return fileList;
    }

}
