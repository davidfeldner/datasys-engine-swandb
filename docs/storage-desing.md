# Catalog storage
We have chosen to go with JSON format catalog storage. The decision was made for easier debugging while developing because if the high readability that JSON offers. We store it as a single file for better portability.

# Catalog contents
The catalog storage file(JSON) contains the path to the binary file with the actual data (catalog content). 
The binary files have the .swan extension. The catalog stores table information like type and name of the columns, and the offset into the binary file. 

# Where the min/max summaries live
Table summaries live in the catalog only. This means min and max summaries are stored together with the table data, in the JSON file. This allows us to quickly decide if a table is needed without opening the data file, and keeps the data files clean.

# Restart handling
The engine should read the catalog storage and .swan files when running a select query on a fresh restart (in theory it should also make do with the catalog storage(JSON) if the chosen table is empty). When writing data we store the entire updated JSON in memory before writing out a new catalog file. 

# Layout inside a partition
We chose row wise layout because we want to make a OLTP style system with many inserts/deletes and updates.

# Partition size
We set the default maximum to 10k because we asked ChatGPT, it said that 1k-100k was good, so we took the middle ground. Furthermore, it was chosen for the practical benefit of still being feasible to scroll through by hand, compared to partitions of size 100k<. Ideally we would benchmark based on our data in the future and the size of our rows to decide a more evidence based estimate. The maximum partition size should be configurable, so tests can set it to a lower value.
The catalog file should store the partition size (in number of rows), and keep track of each partition file offset, for each table, so it can efficiently jump to the partitions needed.

# Value encoding and framing
The start of each .swan file need a header that contains magic bytes to say that it is a swan file, a version number, and 16 bytes for future meta-data.
The data types in the file should be as follows:
LONG as 8-byte two's-complement to support negative numbers efficiently, 
DOUBLE as 8-byte IEEE 754, 
STRING as \[String length as uint16\]\[UTF-8 bytes\]; This means the max length of strings is 65536 characters.

# Byte order
We choose to go with Big-Endian as it is more intuitive for us to read bits left to right. We might have to incorporate a byte swap since our systems are little-endian.
