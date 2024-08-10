package demo.fscatalog.process;

import demo.fscatalog.io.FileIO;

import java.net.URI;

public interface CommitStrategy {
    void commit(FileIO fileIO, URI rootPath) throws Exception;
}
