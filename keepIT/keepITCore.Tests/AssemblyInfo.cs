// The API keeps its data root in a process-wide static (FolderManagement.RootPath), so two API
// hosts running side by side would write each other's media. Hosts therefore run one at a time;
// the suite is small enough that this costs seconds.
[assembly: CollectionBehavior(DisableTestParallelization = true)]
