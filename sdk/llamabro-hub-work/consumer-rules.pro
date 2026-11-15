# WorkManager's own consumer rules keep every ListenableWorker's name and constructor, which the download worker relies
# on (its class name is stored with each queued request); nothing else here uses reflection.
