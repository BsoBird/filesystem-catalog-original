This document records some issues to be discussed and some details not covered in the prototype:

1.All strategies should have a common interface class.

2.**In FileIO, for some complex operations such as LIST, the behavior varies across different FS implementations. Should we merely remind users in the documentation that their behaviors may differ, or should we introduce certain parameters in the method arguments where different parameter combinations represent distinct behaviors, while ensuring that the operation results of all FS implementation classes must exhibit consistent behavior?**
>For example (all examples are in the FileIO interface), such as the method renameFile(URI src, URI dst, boolean overwrite). We use boolean overwrite to indicate whether the rename operation should overwrite. For different OSes, either an UnsupportedOperatorException is thrown, or it is implemented as expected. Or for another example, List<FileEntity> listAllFiles(URI path), We added a doc to this method to alert users that the behavior of this method is inconsistent across different filesystems(The POSIX file system by default only displays one level, while object storage shows all nested levels.). It should be used within the smallest scope to ensure expected results. Between these two, which approach should we adopt? Or a mix of both?


3.For FS CATALOG, an unavoidable issue is the problem of dirty commits. That is to say, we may always successfully commit an old version into the catalog, for example, due to slow IO. If the IO is interrupted at this time, or the entire system is KILL-9, we will inevitably leave behind some old dirty commit-related metadata files. Should we make every effort to clean them up, or simply leave them aside and let users delete such dirty data themselves? If we decide to clean up, should we do it in the commit method, or somewhere else? For different file systems, the cleanup strategies may vary slightly—should they be unified? How to unify them? If we choose not to clean up, what is the rationale? Please consider a maintenance scenario with 1000 tables—would this incur significant costs?

4.How to define a successful submission? Does it count as a successful submission only when written to HINT, or is it considered successful as long as it is written to the earliest file?

5.**Assuming that the fs catalog supports reliable commits for any file system, is there a possibility that the vast majority (or all) of catalog commit operations can be delegated to the fs catalog for execution?**
```
Consider a problem: currently, AWS Glue Catalog does not support safe concurrent submissions from multiple clients. To address this issue, in the iceberg-aws module, we even added a distributed lock implementation for Glue Catalog. However, this did not solve all the problems and increased complexity.

So, let's think from another angle. If the FS Catalog is reliable enough, assuming nothing is done in the Glue Catalog and all submission operations are delegated directly to the FS Catalog, then all the current issues with Glue Catalog would disappear, wouldn't they?

This way, all catalog implementations would become very simple and lightweight. The catalog would only need to cache information from the FS Catalog to speed up client access and delegate all submission operations downstream. The catalog implementation would then be complete. Doing so not only makes the catalog implementation simple and fast, avoiding uneven engineering efforts, but also unlocks the possibility of interoperability between multi-protocol clients.

Since the underlying layer of the catalog is actually the FS Catalog, suppose a customer has built their own catalog and now wants to switch to AWS Glue. The user can quickly specify the FS location of the Glue Catalog and seamlessly take over the data managed by the previous catalog. The reverse is also true.

Additionally, this also solves a series of problems users face when they want to switch catalogs. Due to the current incompatibility between catalogs, users need to migrate both data and metadata. When dealing with large datasets or large partitioned table datasets, this is practically an impossible task.

Apache Paimon has adopted this idea to some extent, though it is still in its early stages (I'm not entirely sure if it was intentional or coincidental). However, it has already achieved the expected results. In terms of catalog extension diversity, Paimon is not far behind Iceberg. Therefore, I believe it at least has the potential for implementation.
```


6.Is it necessary to reduce some race conditions?
>For example, when writing to the trackerFile, if another client is currently writing, causing an exception in the current client, should this exception be ignored? In the test case of the testLocalFileTrackerWithConcurrent method, we can observe that we have 10 clients, each attempting to submit a hundred times, but the final submission count does not reach a hundred. This is because the exception in the trackerFile prevented the clients from submitting, resulting in only about 60 successful submissions. Should we reduce such race conditions? Or is this acceptable as it is?

7.How to take over the old HadoopCatalog/other existing catalogs?
```angular2html
This is a problem...... Should we temporarily write the metadata in duplicate? After we have the data, find a day to replace it? It's similar to the proposal in the recent mailing list.
```
