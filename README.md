# filesystem-catalog-original
A prototype for implementing catalog management based on arbitrary file systems.

We want to minimize the complexity of the infrastructure required to build a datalake.

Our goals are:
- The submission strategy should be pluggable.
- Try to use the same set of submission logic to complete the submission actions in different file systems.

Things yet to be done:
- Multi-table transactions.
- Serializable submission.

see demo.fscatalog.process.FileTrackerCommitStrategyV2

[FileTrackerCommitStrategyV2-Explained.md](FileTrackerCommitStrategyV2-Explained.md)

If you have any ideas, feel free to communicate with me. plashspeed@foxmail.com.
