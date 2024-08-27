package demo.fscatalog.io.entity;

public class FileEntity {
    private String fileName;
    private long lastModified;
    private String absolutePath;

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public String getAbsolutePath() {
        return absolutePath;
    }

    public void setAbsolutePath(String absolutePath) {
        this.absolutePath = absolutePath;
    }

    @Override
    public String toString() {
        return "FileEntity{" +
                "fileName='" + fileName + '\'' +
                ", lastModified=" + lastModified +
                ", absolutePath='" + absolutePath + '\'' +
                '}';
    }
}
