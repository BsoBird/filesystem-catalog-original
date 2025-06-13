package demo.fscatalog.process;

import demo.fscatalog.io.FileIO;
import demo.fscatalog.io.entity.FileEntity;
import demo.fscatalog.io.util.UniIdUtils;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This is a very aggressive submission strategy. We expect only one client to perform a two-phase commit for the same version. If during the submission process, we detect any submission information (files) from other clients under the same version, all clients immediately fail and exit, then proceed to the next submission attempt.
 * 
 * In theory, as long as the file system supports atomic writes, this strategy can be applied to any file system.
 * 
 * A drawback of this strategy is that if a client fails in its previous submission and does not generate a HINT file, the next submission from other clients will still fail. Therefore, other clients need to complete the HINT file generated by the previous submission in their next submission. However, after generating the HINT file, the client must still fail and exit.
 */
public class FileTrackerCommitStrategyV2 implements CommitStrategy{

    public static final String COMMIT_HINT = "COMMIT-HINT.TXT";
    public static final String EXPIRED_HINT = "EXPIRED-HINT.TXT";
    public static final String PRE_COMMIT_PREFIX = "PRE_COMMIT-";
    // just demo,no config
    private static final Integer maxSaveNum = 2;
    private static final Integer maxArchiveSize = 100;
    private static final Integer archiveBatchCleanMaxSize = 20;
    private static final long TTL_PRE_COMMIT = 30*1000L;
    // just demo,no config
    private static final long CLEAN_TTL = 60L * 1000 * 10;

    @Override
    public void commit(FileIO fileIO, URI rootPath) throws Exception {
        URI trackerDir = rootPath.resolve("tracker/");
        URI commitDirRoot = rootPath.resolve("commit/");
        URI archiveDir = rootPath.resolve("archive/");

        fileIO.createDirectory(trackerDir);
        fileIO.createDirectory(commitDirRoot);
        fileIO.createDirectory(archiveDir);

        List<FileEntity> trackerList = fileIO.listAllFiles(trackerDir,false);
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

        // todo: 这里只有提交逻辑,没有写读取逻辑.对于读取逻辑而言,首先我们先从tracker里尝试获取当前最大版本(maxCommitVersion),然后查询这个版本下有没有commitHintFile,那么当发现commitHintFile
        //  不存在时,应当将maxCommitVersion - 1 后读取(我们认为这个最大版本实际上还没有完成有效提交,最新有效版本应当是-1后的版本). 如果-1后依然找不到commitHintFile,
        //  我们就认为这个表可能已经坏了,应当抛出错误.然后什么都不做.(当然,极端情况下,大量的并发提交也可能导致这个问题,但我们在这里先不考虑这个影响,误报就误报了)
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
            fileIO.writeFileWithNoBehaviourPromises(trackerFile,maxCommitVersion+"");
        }

        fileIO.createDirectory(commitRootDirWithTracker);
        fileIO.createDirectory(commitSubTrackerDir);
        fileIO.createDirectory(commitSubHintDir);

        List<FileEntity> subTrackerList =fileIO.listAllFiles(commitSubTrackerDir,false);
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
            fileIO.writeFileWithNoBehaviourPromises(subTrackerFile,subCommitVersion+"");
        }
        fileIO.createDirectory(commitDetailDir);
        List<FileEntity> commitDetails = fileIO.listAllFiles(commitDetailDir,false);
        if(!commitDetails.isEmpty()){
            Map<String,List<FileEntity>> groupedCommitInfo = getCommitInfoByCommitGroup(commitDetails);
            List<List<FileEntity>> counter = groupedCommitInfo.values().stream().filter(x->x.size()==1).collect(Collectors.toList());

            //如果我们发现多个PRE-COMMIT开头的文件,那代表有多个客户端正在提交,这次提交肯定会失败,写入EXPIRE后滚动.
            if(counter.size()==groupedCommitInfo.size() && groupedCommitInfo.size()>1){
                fileIO.writeFileWithNoBehaviourPromises(commitDetailExpireHint,"EXPIRED!");
                throw new ConcurrentModificationException("ConcurrentModificationException!");
            }

            long latestCommitTimestamp = commitDetails.stream().map(FileEntity::getLastModified).max(Long::compareTo).orElse(Long.MAX_VALUE);
            String commitFileName = groupedCommitInfo.keySet().stream().findAny().orElse(null);
            //如果有客户端完成了2阶段提交,但是没写入VERSION-HINT,如果提交文件只有一个客户端写入了2阶段的提交文件,
            //那么补充写入一次VERSION-HINT. 否则,写入EXPIRE标识.滚动至下一个提交空间.
            if(System.currentTimeMillis() - latestCommitTimestamp > TTL_PRE_COMMIT && !fileIO.exists(commitSubHintFile)){
                if(groupedCommitInfo.size()==1 && groupedCommitInfo.get(commitFileName).size()==2){
                    // 如果只有一个分组,那么可能之前的客户端出现了IO异常失败了,由于没有出现并发问题,我们补充填写一次HINT信息.然后失败退出.
                    String hintInfo = commitFileName+"@"+subCommitVersion;
                    fileIO.writeFileWithNoBehaviourPromises(commitSubHintFile,hintInfo);
                    URI debugFile = commitSubHintDir.resolve(commitFileName);
                    // debug一下哪些客户端最终成功提交了,如果我们发现commit文件夹中debug文件数量大于1,则存在问题
                    fileIO.writeFileWithNoBehaviourPromises(debugFile,commitFileName);
                }else{
                    fileIO.writeFileWithNoBehaviourPromises(commitDetailExpireHint,"EXPIRED!");
                }
            }
            throw new ConcurrentModificationException("ConcurrentModificationException!");
        }
        String commitFileName = UniIdUtils.getUniId()+".txt";
        String preCommitFileName = PRE_COMMIT_PREFIX+commitFileName;
        URI preCommitFile = commitDetailDir.resolve(preCommitFileName);
        URI commitFile = commitDetailDir.resolve(commitFileName);
        fileIO.writeFileWithNoBehaviourPromises(preCommitFile,preCommitFileName);
        commitDetails = fileIO.listAllFiles(commitDetailDir,false)
                .stream()
                .filter(x->!x.getFileName().equals(preCommitFileName))
                .collect(Collectors.toList());
        if(!commitDetails.isEmpty()){
//            long latestCommitTimestamp = commitDetails.stream().map(FileEntity::getLastModified).max(Long::compareTo).orElse(Long.MAX_VALUE);
//            if(System.currentTimeMillis() - latestCommitTimestamp > TTL_PRE_COMMIT){
//                fileIO.writeFile(commitDetailExpireHint,"EXPIRED!",false);
//            }
            throw new ConcurrentModificationException("ConcurrentModificationException!");
        }
        fileIO.writeFileWithNoBehaviourPromises(commitFile,commitFileName);
        commitDetails = fileIO.listAllFiles(commitDetailDir,false)
                .stream()
                .filter(x->!x.getFileName().equals(preCommitFileName))
                .filter(x->!x.getFileName().equals(commitFileName))
                .collect(Collectors.toList());
        if(!commitDetails.isEmpty()){
//            long latestCommitTimestamp = commitDetails.stream().map(FileEntity::getLastModified).max(Long::compareTo).orElse(Long.MAX_VALUE);
//            if(System.currentTimeMillis() - latestCommitTimestamp > TTL_PRE_COMMIT){
//                fileIO.writeFile(commitDetailExpireHint,"EXPIRED!",false);
//            }
            throw new ConcurrentModificationException("ConcurrentModificationException!");
        }
        String hintInfo = commitFileName+"@"+subCommitVersion;
        fileIO.writeFileWithNoBehaviourPromises(commitSubHintFile,hintInfo);
        URI debugFile = commitSubHintDir.resolve(commitFileName);
        // debug一下哪些客户端最终成功提交了,如果我们发现commit文件夹中debug文件数量大于1,则存在问题
        fileIO.writeFileWithNoBehaviourPromises(debugFile,commitFileName);

        trackerList = fileIO.listAllFiles(trackerDir,false);

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
        //todo: 小问题:可能无论提交成功与否,客户端都需要写入一次archive,因为过旧的提交总是需要被清理.
        // 在极限情况下,如果一直提交失败,那么过旧的提交就无法被清理了.
        List<FileEntity> needMove2Archive = trackerList.stream()
                .filter(x->{
                    String name = x.getFileName();
                    String versionStr = name.split("\\.")[0];
                    long fileVersion = Long.parseLong(versionStr);
                    return maxVersionAfterCommit -fileVersion > maxSaveNum;
                }).collect(Collectors.toList());

        for (FileEntity archiveFile : needMove2Archive) {
            String expireTimeStamp = String.valueOf(System.currentTimeMillis()+CLEAN_TTL);
            //todo: 在文件名中添加时间戳,通过文件名称就可以提取一些关键信息,例如过期时间,主要是想节省IO,省去一次读取.
            // 但是这样做有个问题,如果多个客户端同时执行move2Archive,
            // 由于多个客户端执行的时间未必相同,同一个tracker可能会产生
            // 多个archive记录.这样会稍微干扰清理.暂时先不管这个问题.
            String archiveFileName = archiveFile.getFileName()+"@"+expireTimeStamp;
            URI dropTracker = trackerDir.resolve(archiveFile.getFileName());
            URI archiveEntity = archiveDir.resolve(archiveFileName);
            if(!fileIO.exists(archiveEntity)){
                fileIO.writeFileWithNoBehaviourPromises(archiveEntity,expireTimeStamp);
            }
            fileIO.delete(dropTracker,false);
        }
    }

    private void cleanTooOldCommit(FileIO fileIO, URI archiveDir, URI commitDirRoot) throws IOException {
        List<FileEntity> archiveList = fileIO.listAllFiles(archiveDir,false);
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
