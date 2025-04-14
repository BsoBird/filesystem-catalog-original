# fs-catalog-original
A prototype for implementing catalog management based on arbitrary file systems.

Our goals are:
- The submission strategy should be pluggable.
- Try to use the same set of submission logic to complete the submission actions in different file systems.

Things yet to be done:
- Multi-table transactions.
- Serializable submission.

see demo.fscatalog.process.FileTrackerCommitStrategyV2
