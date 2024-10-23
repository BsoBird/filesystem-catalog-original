package demo.fscatalog.io.impl;


import demo.fscatalog.io.FileIO;
import demo.fscatalog.io.entity.FileEntity;
import software.amazon.awssdk.auth.credentials.SystemPropertyCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class S3FileIO implements FileIO {
    private static final String S3_SEPARATOR = "/";
    private S3Client s3client;
    private String bucketName;
    @Override
    public synchronized void init(Map<String, String> properties) throws Exception {
        if(s3client == null) {
            bucketName = properties.get("bucket");
            String accessKey = properties.get("accessKey");
            String secretKey = properties.get("secretKey");
            Region region = Region.EU_NORTH_1;
            System.setProperty("aws.accessKeyId", accessKey);
            System.setProperty("aws.secretAccessKey", secretKey);
            SystemPropertyCredentialsProvider provider = SystemPropertyCredentialsProvider.create();
            s3client = S3Client.builder()
                    .credentialsProvider(provider)
                    .region(region)
                    .build();
        }
    }

    @Override
    public void writeFileWithNoBehaviourPromises(URI path, String content) throws IOException {
        writeFile(path, content,true);
    }

    @Override
    public void writeFile(URI path, String content, boolean atomicOverwrite) throws IOException {
        if(!atomicOverwrite){
            throw new UnsupportedOperationException("Only Atomic Overwrite are supported");
        }
        String key = getS3Key(path.getPath());
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        RequestBody requestBody = RequestBody.fromString(content, StandardCharsets.UTF_8);
        s3client.putObject(objectRequest,requestBody);
    }

    @Override
    public void createDirectory(URI path) throws IOException {
        if(!path.getPath().endsWith(S3_SEPARATOR)){
            throw new UnsupportedOperationException("Not a Directory path!");
        }
        writeFileWithNoBehaviourPromises(path,"");
    }

    @Override
    public void delete(URI path, boolean recursion) throws IOException {
        String key = getS3Key(path.getPath());
        if(recursion){
            ListObjectsRequest listObjectsRequest = ListObjectsRequest.builder()
                    .bucket(bucketName).prefix(key).build();
            ListObjectsResponse objectsResponse = s3client.listObjects(listObjectsRequest);

            while (true) {
                ArrayList<ObjectIdentifier> objects = new ArrayList<>();

                for (Iterator<?> iterator = objectsResponse.contents().iterator(); iterator.hasNext(); ) {
                    S3Object s3Object = (S3Object)iterator.next();
                    objects.add(ObjectIdentifier.builder().key(s3Object.key()).build());
                }

                s3client.deleteObjects(
                        DeleteObjectsRequest.builder().bucket(bucketName).delete(Delete.builder().objects(objects).build()).build()
                );
                if (objectsResponse.isTruncated()) {
                    objectsResponse = s3client.listObjects(listObjectsRequest);
                    continue;
                }
                break;
            }
        }else{
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3client.deleteObject(deleteObjectRequest);
        }
    }

    @Override
    public void close() throws IOException {
        if(s3client!=null){
            s3client.close();
        }
    }

    @Override
    public String read(URI path) throws IOException {
        String key = getS3Key(path.getPath());
        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .key(key)
                .bucket(bucketName)
                .build();

        String line = null;
        StringBuilder sb = new StringBuilder();
        try(ResponseInputStream<GetObjectResponse> getRespStream =  s3client.getObject(objectRequest);
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(getRespStream, StandardCharsets.UTF_8));){
            while((line = bufferedReader.readLine()) != null){
                sb.append(line);
            }
        }
        return sb.toString();
    }

    @Override
    public List<FileEntity> listAllFiles(URI path, boolean recursion) throws IOException {
        String key = getS3Key(path.getPath());
        ListObjectsV2Request initialRequest = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(key)
                .maxKeys(100)
                .build();
        List<FileEntity> result = new ArrayList<>();
        ListObjectsV2Iterable paginator = s3client.listObjectsV2Paginator(initialRequest);
        for (ListObjectsV2Response listObjectsV2Response : paginator) {
            for (S3Object content : listObjectsV2Response.contents()) {
                String contentKey = content.key();
                long lastModified = content.lastModified().toEpochMilli();
                if(recursion){
                    String fileName = contentKey.substring(contentKey.lastIndexOf(S3_SEPARATOR)+1);
                    if(fileName.isEmpty()){
                        continue;
                    }
                    FileEntity entity = new FileEntity();
                    entity.setFileName(fileName);
                    entity.setLastModified(lastModified);
                    entity.setAbsolutePath(S3_SEPARATOR+contentKey);
                    result.add(entity);
                }else{
                    String rootPath = getS3Key(path.getPath());
                    String suffix = contentKey.substring(rootPath.length());
                    if(!suffix.trim().isEmpty() && !suffix.contains(S3_SEPARATOR)){
                        FileEntity entity = new FileEntity();
                        entity.setFileName(suffix);
                        entity.setLastModified(lastModified);
                        entity.setAbsolutePath(S3_SEPARATOR+contentKey);
                        result.add(entity);
                    }
                }
            }
        }
        return result;
    }

    @Override
    public boolean exists(URI path) throws IOException {
        String key = getS3Key(path.getPath());
        try {
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3client.headObject(headObjectRequest);
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            } else {
                throw e;
            }
        }
    }

    private String getS3Key(String path) {
        String bucketPath = S3_SEPARATOR+bucketName;
        if(path.startsWith(bucketPath)){
            path = path.substring(bucketPath.length());
        }
        if (path.startsWith(S3_SEPARATOR)) {
            return path.substring(1);
        }
        return path;
    }

}
