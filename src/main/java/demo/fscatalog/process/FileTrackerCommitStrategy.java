package demo.fscatalog.process;

import demo.fscatalog.io.FileIO;
import demo.fscatalog.io.entity.FileEntity;
import demo.fscatalog.io.entity.Pair;
import demo.fscatalog.io.util.UniIdUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * Committing using the FileTracker policy is safe as long as the file system provides single-file
 * atomic write operations and list operations. It is thread-safe(may not.....).
 * This policy applies to almost all file systems(maybe this strategy is a bad strategy).
 *
 * note: 根据测试结果,我们发现了一个问题,在对象存储系统中,文件写入时,别的客户端并不是立刻可见.
 * 这导致list出来的结果与实际的文件有一定偏差.
 * 因为对象存储内,元数据同步的耗时不可忽略,且不可控.
 * 例如,写入一个文件,我们可能15:35才能看到15:30写入的文件.并且这个文件的时间还是15:30.
 * 因此我们无法准确地衡量时间.
 * 除非我们将 getFileSystemTimeAccuracy()的值给的很大,但这样又很卡提交.
 *
 * 算了,废弃吧.这个方案不是非常可靠.
 */
@Deprecated
public class FileTrackerCommitStrategy implements CommitStrategy{
    public static final String COMMIT_HINT = "COMMIT-HINT.TXT";
    // just demo,no config
    private static final Integer maxSaveNum = 2;
    private static final Integer maxArchiveSize = 100;
    private static final Integer archiveBatchCleanMaxSize = 20;
    // just demo,no config
    private static final long CLEAN_TTL = 30 * 1000;

    /*
    * 这个策略是使用commit文件的最小时间来确定哪一次提交是成功的提交.
    * 因此每一次提交都是一个文件夹,每一个客户端都向这个文件夹写入commit记录,
    * 在这个文件夹下,时间最早,并且名称字典序最小的commit记录是成功的记录.
    * 在每一个文件夹下,我们都写入一个HINT文件来协助客户端快速定位应该读取某个版本下哪个Hint.
    *
    * 由于我们使用 list操作+最小时间+最小名称 的方案确定提交,因此这套方案基本没有任何并发问题.
    * 它是线程安全的.并且基本所有文件系统都支持这种操作.
    *
    * 它大体的结构如下:
    * db/table/
    *         commit/
    *               v0/
    *                   uuid01-commit.json
    *                   uuid02-commit.json
    *                   commit-hint.txt ---> uuid01-commit.json
    *               v1/
    *                   uuid01-commit.json
    *                   uuid02-commit.json
    *                   commit-hint.txt ---> uuid02-commit.json
    *          tracker/
    *                v0.txt
    *                v1.txt
    *
    *
    * 理论上,确定最新的commit版本,需要list commit文件夹下 V0 V1....这类的子文件夹,拿到最大的版本.
    * 但是不是所有的文件系统都支持列出子文件夹,例如对象存储.
    * 因此我们采用了一个更加保守的方案,我们使用tracker文件来跟踪有什么子文件夹.
    * tracker文件夹中记录的跟踪记录可以多于实际commit的记录,但仅限多一个版本.不能少于实际commit记录.
    * 这是一套灵感来自于hbase的方案. hbase可以使用tracker来实现基于s3的数据管理.我们当然也可以.
    *
    * */

    @Override
    public synchronized void commit(FileIO fileIO, URI rootPath) throws Exception {
        URI trackerDir = rootPath.resolve("tracker/");
        URI commitDirRoot = rootPath.resolve("commit/");
        URI archiveDir = rootPath.resolve("archive/");
        fileIO.createDirectory(trackerDir);
        fileIO.createDirectory(commitDirRoot);
        fileIO.createDirectory(archiveDir);
        // write-tracker
        Pair<Long,List<FileEntity>> commitInfoBeforeCommit = findNextCommitInfo(fileIO,trackerDir,commitDirRoot);
        long version = commitInfoBeforeCommit.getKey();
        String hintVersion = String.valueOf(version);
        // 加后缀是为了规避对象存储使用前缀list时,筛选到预期之外文件的问题.
        // 例如,我想删除 /data/01文件 但是会误删 /data/011.
        // 因此目前是添加了后缀来限制行为.但是否需要在fileIO中规整这类细节?
        String hintVersionFileName = hintVersion+".txt";
        URI trackerFile = trackerDir.resolve(hintVersionFileName);
        if(!fileIO.exists(trackerFile)){
            fileIO.writeFile(trackerFile,hintVersion,false);
        }
        URI commitDir = commitDirRoot.resolve(hintVersion+"/");
        List<FileEntity> alreadyExistsCommit = commitInfoBeforeCommit.getValue();
        URI commitHint = commitDir.resolve(COMMIT_HINT);
        fastFailIfCommitInfoTooOld(fileIO, alreadyExistsCommit, commitDir, commitHint);
        // do-commit
        // 根据tracker的结果 定位到commit文件夹,然后写入一个提交记录.
        String commitFileName = writeCommitInfo(fileIO, commitDir, hintVersion);
        // check current commit
        // 写入完提提交结果后,等待一段时间,然后查看自己的提交是不是正确的提交.
        // 如果不是,提交失败. 否则,开始写入HINT,提交实际上就成功了.
        checkCommitSuccess(fileIO, commitDir, commitFileName, commitHint);

        try{
            // 这部分逻辑属于扫尾工作.
            // 除过一些特殊的场景需要抛出异常,剩下的情况,应当吞咽所有异常,因为提交已经成功.
            // 我们只是收尾工作没有做好而已.可以下次再做.
            //check if dirty commit
            Pair<Long,List<FileEntity>> resultPair = findNextCommitInfo(fileIO,trackerDir,commitDirRoot);
            long maxVersionAfterCommit = resultPair.getKey();
            fastFailIfDirtyCommit(fileIO, maxVersionAfterCommit, version, commitDir, trackerFile);
            List<FileEntity> trackerList = fileIO.listAllFiles(trackerDir,false);
            // 我们是否要清理脏提交?
            // 详见问题3，在这个原型中,我们选择了清理. 我们模仿了hbase的行为.
            // 如果我们要删除一个记录,我们先将它们移动到一个地方记录,然后找个时间删除掉.
            // 一般需要等待较长的时间.
            // 这样做是尽可能避免: 如果我出现了问题3所提到的问题,我先删除,
            // 但有一个慢客户端在我完成删除后才完成写入. 那么这个慢客户端
            // 写入的文件会一直留存在文件系统中.不好清理,代价偏大.、
            // 慢IO不可能卡很久.因此我们只需要卡一个足够久的时间,就可以解决99%的问题.
            // 剩下的1% 人工支持都行.
            // 移动到archive后, tracker 中的记录减少了,我们降低了commit时的扫描代价.
            // move archive
            moveTooOldTracker2Archive(fileIO, trackerList, maxVersionAfterCommit, archiveDir, trackerDir);
            cleanTooOldCommit(fileIO, archiveDir, commitDirRoot);
        }catch (Exception e){
            // 提交成功后,除非必要,我们一般不抛出任何异常.
            // 这里只是演示一下.
//            throw new RuntimeException("demo error",e);
            e.printStackTrace();
        }
    }

    private void cleanTooOldCommit(FileIO fileIO, URI archiveDir, URI commitDirRoot) throws IOException {
        List<FileEntity> archiveList = fileIO.listAllFiles(archiveDir,false);
        archiveList.sort(Comparator.comparing((x)-> Long.parseLong(x.getFileName().split("\\.")[0])));
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

    private void moveTooOldTracker2Archive(FileIO fileIO, List<FileEntity> trackerList, long maxVersionAfterCommit, URI archiveDir, URI trackerDir) throws IOException {
        List<FileEntity> needMove2Archive = trackerList.stream()
                .filter(x->{
                    String name = x.getFileName();
                    String versionStr = name.split("\\.")[0];
                    long fileVersion = Long.parseLong(versionStr);
                    return maxVersionAfterCommit -fileVersion > maxSaveNum;
                }).collect(Collectors.toList());

        for (FileEntity archiveFile : needMove2Archive) {
            String expireTimeStamp = String.valueOf(System.currentTimeMillis()+CLEAN_TTL);
            String archiveFileName = archiveFile.getFileName()+"@"+expireTimeStamp;
            URI dropTracker = trackerDir.resolve(archiveFile.getFileName());
            URI archiveEntity = archiveDir.resolve(archiveFileName);
            if(!fileIO.exists(archiveEntity)){
                fileIO.writeFile(archiveEntity,expireTimeStamp,false);
            }
            fileIO.delete(dropTracker,false);
        }
    }

    private void fastFailIfDirtyCommit(FileIO fileIO, long maxVersionAfterCommit, long version, URI commitDir, URI trackerFile) throws IOException {
        if(maxVersionAfterCommit - version > maxSaveNum){
            fileIO.delete(commitDir,true);
            fileIO.delete(trackerFile,false);
            throw new RuntimeException("Dirty Commit!");
        }
    }

    private void checkCommitSuccess(FileIO fileIO, URI commitDir, String commitFileName, URI commitHint) throws InterruptedException, IOException {
        TimeUnit.MILLISECONDS.sleep(fileIO.getFileSystemTimeAccuracy());
        List<FileEntity> fileEntityList = fileIO.listAllFiles(commitDir,false);
        FileEntity earliestCommitFile = findEarliestCommit(fileEntityList);
        if(earliestCommitFile==null){
            throw new RuntimeException("The commit dir disappeared, which shouldn't have happened.");
        }
        if(!earliestCommitFile.getFileName().equals(commitFileName)){
            String msg = String.format("Commit Failed,Hint[%s],Commit[%s]",earliestCommitFile.getFileName(),commitFileName);
            throw new RuntimeException(msg);
        }else{
            try{
                fileIO.writeFile(commitHint, commitFileName,false);
            }catch (FileAlreadyExistsException e){
                 // do-nothing
                // 可能有别的客户端跑到了提交失败并且补充HINT的逻辑. 它们也在写入HINT.
                // 如果HINT已经存在,忽略这个异常.
            }catch (IOException e){
                // 如果文件系统出现IO异常,查看一次HINT是否被写入,如果写入失败,抛出提交失败异常.
                if(!fileIO.exists(commitHint)){
                    throw e;
                }
            }catch (Exception e){
                // 客户端出现问题了,无法确认文件系统给是否真的写入成功,这里抛出commitStateUnknown异常.
                // 也就是说,抛出一个特殊的异常,外层引擎识别到此异常,不进行任何数据清理.
                throw new RuntimeException("commit state unknown",e);
            }
        }
    }

    private String writeCommitInfo(FileIO fileIO, URI commitDir, String hintVersion) throws IOException {
        fileIO.createDirectory(commitDir);
        String commitFileName = UniIdUtils.getUniId();
        URI commitFile = commitDir.resolve(commitFileName);
        String content = String.format("CommitVersion:[%s],commitFile:[%s]", hintVersion,commitFileName);
        fileIO.writeFile(commitFile,content,false);
        return commitFileName;
    }

    private void fastFailIfCommitInfoTooOld(FileIO fileIO, List<FileEntity> alreadyExistsCommit, URI commitDir, URI commitHint) throws InterruptedException, IOException {
        // 如果这个版本下已经存在提交了,那么本次提交其实已经失败了.因为这次提交不可能是时间最早的提交了.
        // 这里存在两种可能,第一种情况是,所有的客户端都正常,只是这个瞬间没来得及写入HINT.
        // 第二种情况是,之前最早提交的客户端写HINT时挂了.没有客户端去写HINT.
        // 因此无论无何,我们需要补一次HINT文件. 然后抛出提交失败的异常.
        if(!alreadyExistsCommit.isEmpty()){
            // 如果tracker记录的文件夹下存在提交记录, 先去查看是否存在commitHint文件
            // 如果存在commitHint文件,直接退出 抛出提交失败异常.
            FileEntity commitHintEntity =  alreadyExistsCommit.stream().filter(x->x.getFileName().equals(COMMIT_HINT)).findAny().orElse(null);
            if(commitHintEntity==null){
                // 如果不存在commitHint文件,我们假设有别的客户端正在写入新的commitHint
                // 那么我们可以等待一定的时间后,再次查看是否有Hint被写入.
                // 如果此时有Hint,那么退出并抛出提交失败异常.
                // 如果依然没有HINT,那么寻找到时间最小,名称最小的文件,写HINT,然后失败抛出异常.
                TimeUnit.MICROSECONDS.sleep(fileIO.getFileSystemTimeAccuracy());
                List<FileEntity> commits = fileIO.listAllFiles(commitDir,false);
                FileEntity checkHintAgain =  commits.stream().filter(x->x.getFileName().equals(COMMIT_HINT)).findAny().orElse(null);
                if(checkHintAgain==null){
                    FileEntity earliestCommitFile = findEarliestCommit(commits);
                    if(earliestCommitFile==null){
                        throw new RuntimeException("The commit dir disappeared, which shouldn't have happened.");
                    }
                    // 也可以不检查,直接写入.但写入前本身就要List一次寻找问最小的文件,check可以顺手做掉.
                    if(!fileIO.exists(commitHint)){
                        fileIO.writeFile(commitHint,earliestCommitFile.getFileName(),false);
                    }
                }
            }
            throw new RuntimeException("Commit failed exception!too old commit!");
        }
    }

    FileEntity findEarliestCommit(List<FileEntity> commitEntity){
        Map<Long,List<FileEntity>> groupedResult = new HashMap<>();
        commitEntity.forEach(x->{
            if(!x.getFileName().equals(COMMIT_HINT)){
                groupedResult.computeIfAbsent(x.getLastModified(), k -> new ArrayList<>());
                groupedResult.get(x.getLastModified()).add(x);
            }
        });
        long earliestFileTime = groupedResult.keySet().stream().min(Long::compareTo).orElse(Long.MAX_VALUE);
        List<FileEntity> filteredGroup = groupedResult.getOrDefault(earliestFileTime,new ArrayList<>());

        filteredGroup.sort(Comparator.comparing(FileEntity::getFileName));
        return filteredGroup.stream().findFirst().orElse(null);
    }

    private Pair<Long,List<FileEntity>> findNextCommitInfo(FileIO fileIO, URI trackerDir, URI commitDir) throws IOException {
        Pair<Long,List<FileEntity>> pair = new Pair<>();
        List<FileEntity> commitVersionHints = fileIO.listAllFiles(trackerDir,false);
        long maxVersion = commitVersionHints.stream().map(x->Long.parseLong(x.getFileName().split("\\.")[0]))
                .max(Long::compareTo)
                .orElse(0L);
        List<FileEntity> commitDetails = fileIO.listAllFiles(getCommitDir(commitDir,maxVersion),false);
        FileEntity hintFile = commitDetails
                .stream()
                .filter(x->COMMIT_HINT.equals(x.getFileName()))
                .findAny()
                .orElse(null);
        commitDetails.remove(hintFile);
        pair.setValue(commitDetails);
        long commitVersion = maxVersion;
        if(hintFile!=null){
            commitVersion++;
            pair.setValue(new ArrayList<>());
        }
        pair.setKey(commitVersion);
        return pair;
    }

    private URI getCommitDir(URI commitRootPath,long commitVersion) {
        return commitRootPath.resolve(commitVersion +"/");
    }
}
