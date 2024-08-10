package demo.fscatalog.process;

import demo.fscatalog.io.FileIO;

import java.net.URI;

/**
 * Use the rename operation for commit operations.
 * The file system needs to provide a rename operation that does not overwrite the target file.
 * It is thread-safe.
 * This policy applies to linux, HDFS and some object storage systems that support mutex operations.
 */
public class RenameCommitStrategy implements CommitStrategy{
    @Override
    public void commit(FileIO fileIO, URI rootPath) throws Exception {
        // 这块我就不写了,与hadoopTableOptions类似
        // 可以看这里 https://github.com/apache/hive/pull/5349/files#diff-d44610e7198eef2f0c3667bc4ab8c10fa1cb4a2d655db9bfd5524ef93ba7c3ab
    }
}
