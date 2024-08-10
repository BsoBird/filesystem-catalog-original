package demo.fscatalog.io.impl;

import com.aliyun.oss.*;
import com.aliyun.oss.internal.OSSHeaders;
import com.aliyun.oss.model.*;
import demo.fscatalog.io.entity.FileEntity;
import demo.fscatalog.io.FileIO;

import java.io.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OSSFileIO implements FileIO {
    private static final String OSS_SEPARATOR = "/";
    private String bucketName;
    private OSS oss = null;
    @Override
    public void init(Map<String, String> properties) {
        String endpoint = properties.get("endpoint");
        String accessKeyId = properties.get("accessKeyId");
        String accessKeySecret =properties.get("accessKeySecret");
        bucketName = properties.get("bucketName");
        final ClientBuilderConfiguration clientBuilderConfiguration = new ClientBuilderConfiguration();
        oss = new OSSClientBuilder()
                .build(endpoint, accessKeyId, accessKeySecret, clientBuilderConfiguration);
    }

    @Override
    public String read(URI path) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line = null;
        try(OSSObject ossObject = oss.getObject(bucketName,getOssKey(path.getPath()));
            BufferedReader br = new BufferedReader(new InputStreamReader(ossObject.getObjectContent()))){
            while((line = br.readLine())!=null){
                sb.append(line);
            }
        } catch (OSSException | ClientException oe) {
            throw new IOException(oe);
        }
        return sb.toString();
    }

    @Override
    public boolean exists(URI path) throws IOException {
        String pathStr = path.getPath();
        String key = getOssKey(pathStr);
        return oss.doesObjectExist(bucketName,key);
    }

    @Override
    public void writeFile(URI path, String content, boolean overwrite) {
        String pathStr = path.getPath();
        String key = getOssKey(pathStr);
        final PutObjectRequest request = new PutObjectRequest(bucketName, key, new ByteArrayInputStream(content.getBytes()));
        request.setMetadata(getOssDefaultMetadata(overwrite));
        oss.putObject(request);
    }

    @Override
    public void createDirectory(URI path) throws IOException {
        if(!path.getPath().endsWith(OSS_SEPARATOR)){
            throw new IllegalArgumentException("not a directory path");
        }
        if(!exists(path)){
            writeFile(path,"",false);
        }
    }

    @Override
    public void delete(URI path) {
        String nextMarker = null;
        int maxKeys = 200;
        ObjectListing objectListing = null;
        String prefix = getOssKey(path.getPath());
        do {
            ListObjectsRequest listObjectsRequest = new ListObjectsRequest(bucketName)
                    .withPrefix(prefix)
                    .withMarker(nextMarker)
                    .withMaxKeys(maxKeys);
            objectListing = oss.listObjects(listObjectsRequest);
            if (!objectListing.getObjectSummaries().isEmpty()) {
                List<String> keys = new ArrayList<>();
                for (OSSObjectSummary s : objectListing.getObjectSummaries()) {
                    keys.add(s.getKey());
                }
                DeleteObjectsRequest deleteObjectsRequest = new DeleteObjectsRequest(bucketName).withKeys(keys).withEncodingType("url");
                oss.deleteObjects(deleteObjectsRequest);
            }
            nextMarker = objectListing.getNextMarker();
        } while (objectListing.isTruncated());
    }

    private String getOssKey(String path) {
        String bucketPath = OSS_SEPARATOR+bucketName;
        if(path.startsWith(bucketPath)){
            path = path.substring(bucketPath.length());
        }
        if (path.startsWith(OSS_SEPARATOR)) {
            return path.substring(1);
        }
        return path;
    }

    private static ObjectMetadata getOssDefaultMetadata(boolean overwrite) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setHeader(OSSHeaders.OSS_STORAGE_CLASS, StorageClass.Standard.toString());
        metadata.setObjectAcl(CannedAccessControlList.PublicRead);
        if (!overwrite) {
            metadata.setHeader("x-oss-forbid-overwrite", "true");
        }
        return metadata;
    }

    @Override
    public void close() throws IOException {
        oss.shutdown();
    }

    @Override
    public List<FileEntity> listAllFiles(URI path) {
        String nextMarker = null;
        ObjectListing objectListing;
        List<FileEntity> result = new ArrayList<>();
        int maxKeys = 200;
        String pathStr = getOssKey(path.getPath());
        do {
            ListObjectsRequest listObjectsRequest =  new ListObjectsRequest(bucketName).withMarker(nextMarker).withMaxKeys(maxKeys);
            listObjectsRequest.setPrefix(pathStr);
//            listObjectsRequest.setDelimiter("/");
            objectListing = oss.listObjects(listObjectsRequest);
            List<OSSObjectSummary> sums = objectListing.getObjectSummaries();
            for (OSSObjectSummary s : sums) {
                String key = s.getKey();
                long lastModified = s.getLastModified().getTime();
                String fileName = key.substring(key.lastIndexOf(OSS_SEPARATOR)+1);
                if(fileName.isEmpty()){
                    continue;
                }
                FileEntity entity = new FileEntity();
                entity.setFileName(fileName);
                entity.setLastModified(lastModified);
                result.add(entity);
            }
            nextMarker = objectListing.getNextMarker();
        } while (objectListing.isTruncated());
        return result;
    }
}
