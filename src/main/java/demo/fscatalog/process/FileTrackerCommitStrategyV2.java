package demo.fscatalog.process;

import demo.fscatalog.io.FileIO;
import demo.fscatalog.io.entity.FileEntity;
import demo.fscatalog.io.util.UniIdUtils;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 这是一种非常激进的提交策略,我们期望只有一个客户端进行两阶段提交.
 * 如果我们发现任何其他客户端的提交信息,则立刻失败并退出.
 *
 * 理论上,只要文件系统支持原子写入,那么它可以适用与任何文件系统.
 *
 * 这个策略有一个坏处,如果上一次提交失败了,并且没有产生HINT文件,那么下一次提交还是会失败.
 * 因此需要下一次提交产生HINT文件,但是产生HINT文件走的是失败退出的逻辑.
 *
 */
public class FileTrackerCommitStrategyV2 implements CommitStrategy{

    public static final String COMMIT_HINT = "COMMIT-HINT.TXT";
    public static final String EXPIRED_HINT = "EXPIRED-HINT.TXT";
    public static final String PRE_COMMIT_PREFIX = "PRE_COMMIT-";
    // just demo,no config
    private static final Integer maxSaveNum = 2;
    private static final Integer maxArchiveSize = 100;
    private static final Integer archiveBatchCleanMaxSize = 20;
    private static final long TTL_PRE_COMMIT = 30 * 1000;
    // just demo,no config
    private static final long CLEAN_TTL = 30L * 1000;

    @Override
    public void commit(FileIO fileIO, URI rootPath) throws Exception {
        URI trackerDir = rootPath.resolve("tracker/");
        URI commitDirRoot = rootPath.resolve("commit/");
        URI archiveDir = rootPath.resolve("archive/");

        fileIO.createDirectory(trackerDir);
        fileIO.createDirectory(commitDirRoot);
        fileIO.createDirectory(archiveDir);

        List<FileEntity> trackerList = fileIO.listAllFiles(trackerDir);
        long maxCommitVersion = trackerList
                .stream()
                .map(x->Long.parseLong(x.getFileName().split("\\.")[0]))
                .max(Long::compareTo)
                .orElse(0L);

        URI trackerFile = trackerDir.resolve(maxCommitVersion+".txt");
        URI commitRootDirWithTracker = commitDirRoot.resolve(maxCommitVersion+"/");

        URI commitSubTrackerDir = commitRootDirWithTracker.resolve("sub-tracker/");
        URI commitSubHintDir = commitRootDirWithTracker.resolve("sub-hint/");
        URI commitSubHintFile = commitSubHintDir.resolve(COMMIT_HINT);

        // todo: 如果是客户端读取最新版本,而不是执行提交,那么当发现commitSubHintFile
        //  不存在时,应当将maxCommitVersion - 1 后读取. 如果依然摘不到commitHint,
        //  应当抛出错误.
        if(fileIO.exists(commitSubHintFile)){
            maxCommitVersion++;
            // 只向前滚动一次
            trackerFile = trackerDir.resolve(maxCommitVersion+".txt");
            commitRootDirWithTracker = commitDirRoot.resolve(maxCommitVersion+"/");

            commitSubTrackerDir = commitRootDirWithTracker.resolve("sub-tracker/");
            commitSubHintDir = commitRootDirWithTracker.resolve("sub-hint/");
            commitSubHintFile = commitSubHintDir.resolve(COMMIT_HINT);
        }


        if(!fileIO.exists(trackerFile)){
            fileIO.writeFile(trackerFile,maxCommitVersion+"",false);
        }

        fileIO.createDirectory(commitRootDirWithTracker);
        fileIO.createDirectory(commitSubTrackerDir);
        fileIO.createDirectory(commitSubHintDir);

        List<FileEntity> subTrackerList =fileIO.listAllFiles(commitSubTrackerDir);
        long subCommitVersion = subTrackerList
                .stream()
                .map(x->Long.parseLong(x.getFileName().split("\\.")[0]))
                .max(Long::compareTo)
                .orElse(0L);

        URI subTrackerFile = commitSubTrackerDir.resolve(subCommitVersion+".txt");
        URI commitDetailDir = commitRootDirWithTracker.resolve(subCommitVersion+"/");
        URI commitDetailExpireHint = commitDetailDir.resolve(EXPIRED_HINT);

        if(fileIO.exists(commitDetailExpireHint)){
            subCommitVersion++;
            subTrackerFile = commitSubTrackerDir.resolve(subCommitVersion+".txt");
            commitDetailDir = commitRootDirWithTracker.resolve(subCommitVersion+"/");
            commitDetailExpireHint = commitDetailDir.resolve(EXPIRED_HINT);
        }

        if(!fileIO.exists(subTrackerFile)){
            fileIO.writeFile(subTrackerFile,subCommitVersion+"",false);
        }
        fileIO.createDirectory(commitDetailDir);
        List<FileEntity> commitDetails = fileIO.listAllFiles(commitDetailDir);
        if(!commitDetails.isEmpty()){
            long latestCommitTimestamp = commitDetails.stream().map(FileEntity::getLastModified).max(Long::compareTo).orElse(Long.MAX_VALUE);
            if(System.currentTimeMillis() - latestCommitTimestamp > TTL_PRE_COMMIT){
                Map<String,List<FileEntity>> groupedCommitInfo = getCommitInfoByCommitGroup(commitDetails);
                if(groupedCommitInfo.size()==1){
                    String commitFileName = groupedCommitInfo.keySet().stream().findAny().orElse(null);
                    if(groupedCommitInfo.get(commitFileName).size()==2){
                        // 如果只有一个分组,那么可能之前的客户端出现了IO异常失败了,由于没有出现并发问题,我们补充填写一次HINT信息.然后失败退出.
                        String hintInfo = commitFileName+"@"+subCommitVersion;
                        fileIO.writeFile(commitSubHintFile,hintInfo,false);
                        URI debugFile = commitSubHintDir.resolve(commitFileName);
                        // debug一下哪些客户端最终成功提交了,如果我们发现commit文件夹中debug文件数量大于1,则存在问题
                        fileIO.writeFile(debugFile,commitFileName,false);
                    }else{
                        fileIO.writeFile(commitDetailExpireHint,"EXPIRED!",false);
                    }
                }else{
                    fileIO.writeFile(commitDetailExpireHint,"EXPIRED!",false);
                }
            }
            throw new IllegalStateException("存在多个客户端同时提交!");
        }
        String commitFileName = UniIdUtils.getUniId()+".txt";
        String preCommitFileName = PRE_COMMIT_PREFIX+commitFileName;
        URI preCommitFile = commitDetailDir.resolve(preCommitFileName);
        URI commitFile = commitDetailDir.resolve(commitFileName);
        fileIO.writeFile(preCommitFile,preCommitFileName,false);
        commitDetails = fileIO.listAllFiles(commitDetailDir)
                .stream()
                .filter(x->!x.getFileName().equals(preCommitFileName))
                .collect(Collectors.toList());
        if(!commitDetails.isEmpty()){
//            long latestCommitTimestamp = commitDetails.stream().map(FileEntity::getLastModified).max(Long::compareTo).orElse(Long.MAX_VALUE);
//            if(System.currentTimeMillis() - latestCommitTimestamp > TTL_PRE_COMMIT){
//                fileIO.writeFile(commitDetailExpireHint,"EXPIRED!",false);
//            }
            throw new IllegalStateException("存在多个客户端同时提交!");
        }
        fileIO.writeFile(commitFile,commitFileName,false);
        commitDetails = fileIO.listAllFiles(commitDetailDir)
                .stream()
                .filter(x->!x.getFileName().equals(preCommitFileName))
                .filter(x->!x.getFileName().equals(commitFileName))
                .collect(Collectors.toList());
        if(!commitDetails.isEmpty()){
//            long latestCommitTimestamp = commitDetails.stream().map(FileEntity::getLastModified).max(Long::compareTo).orElse(Long.MAX_VALUE);
//            if(System.currentTimeMillis() - latestCommitTimestamp > TTL_PRE_COMMIT){
//                fileIO.writeFile(commitDetailExpireHint,"EXPIRED!",false);
//            }
            throw new IllegalStateException("存在多个客户端同时提交!");
        }
        String hintInfo = commitFileName+"@"+subCommitVersion;
        fileIO.writeFile(commitSubHintFile,hintInfo,false);
        URI debugFile = commitSubHintDir.resolve(commitFileName);
        // debug一下哪些客户端最终成功提交了,如果我们发现commit文件夹中debug文件数量大于1,则存在问题
        fileIO.writeFile(debugFile,commitFileName,false);

        trackerList = fileIO.listAllFiles(trackerDir);

        moveTooOldTracker2Archive(fileIO,trackerList,maxCommitVersion,archiveDir,trackerDir);
        cleanTooOldCommit(fileIO,archiveDir,commitDirRoot);
    }

    private Map<String,List<FileEntity>> getCommitInfoByCommitGroup(List<FileEntity> fileEntityList){
        Map<String,List<FileEntity>> result = new HashMap<>();
        fileEntityList.stream()
                .filter(x->!EXPIRED_HINT.equals(x.getFileName()))
                .forEach(x->{
                    String key = x.getFileName();
                    if(key!=null){
                        if(key.startsWith(PRE_COMMIT_PREFIX)){
                            key = key.substring(PRE_COMMIT_PREFIX.length());
                        }
                        result.computeIfAbsent(key, k->new ArrayList<>()).add(x);
                    }
                });
        return result;
    }


    private void moveTooOldTracker2Archive(FileIO fileIO, List<FileEntity> trackerList, long maxVersionAfterCommit, URI archiveDir, URI trackerDir) throws IOException {
        //todo: 无论提交成功与否,可能客户端都需要写入一次archive
        List<FileEntity> needMove2Archive = trackerList.stream()
                .filter(x->{
                    String name = x.getFileName();
                    String versionStr = name.split("\\.")[0];
                    long fileVersion = Long.parseLong(versionStr);
                    return maxVersionAfterCommit -fileVersion > maxSaveNum;
                }).collect(Collectors.toList());

        for (FileEntity archiveFile : needMove2Archive) {
            String expireTimeStamp = String.valueOf(System.currentTimeMillis()+CLEAN_TTL);
            //todo: 在文件名中添加时间戳,主要是想节省IO,通过文件名称就可以提取一些关键信息.
            // 但是这样做有个问题,如果多个客户端同时执行move2Archive,同一个tracker可能会产生
            // 多个archive记录.这样会稍微干扰清理.暂时先不管这个问题.
            String archiveFileName = archiveFile.getFileName()+"@"+expireTimeStamp;
            URI dropTracker = trackerDir.resolve(archiveFile.getFileName());
            URI archiveEntity = archiveDir.resolve(archiveFileName);
            if(!fileIO.exists(archiveEntity)){
                fileIO.writeFile(archiveEntity,expireTimeStamp,false);
            }
            fileIO.delete(dropTracker,false);
        }
    }

    private void cleanTooOldCommit(FileIO fileIO, URI archiveDir, URI commitDirRoot) throws IOException {
        List<FileEntity> archiveList = fileIO.listAllFiles(archiveDir);
        archiveList.sort(Comparator.comparing(x-> Long.parseLong(x.getFileName().split("\\.")[0])));
        int maxCleanTimes = Math.min(1,archiveList.size());
        if(archiveList.size()>maxArchiveSize){
            //多线程的情况下,如果一个一个删除,那么可能删除速度跟不上写入速度.
            //所以这里加了一个批处理.
            maxCleanTimes = Math.min(archiveBatchCleanMaxSize,archiveList.size());
        }
        for(int i=0;i<maxCleanTimes;i++){
            FileEntity cleanFile = archiveList.get(i);
            if(cleanFile!=null){
                String fileName = cleanFile.getFileName();
                URI archiveFile = archiveDir.resolve(fileName);
                long expireTimestamp = Long.parseLong(fileName.split("@")[1]);
                if(System.currentTimeMillis()>expireTimestamp){
                    String dropVersion = fileName.split("\\.")[0];
                    URI oldCommitDir = commitDirRoot.resolve(dropVersion+"/");
                    fileIO.delete(oldCommitDir,true);
                    fileIO.delete(archiveFile,false);
                }
            }
        }
    }



}
