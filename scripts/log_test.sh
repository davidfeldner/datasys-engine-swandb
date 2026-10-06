# 1. Start from a clean live log so no test-written lines are in it
mv logs/engine.log logs/engine-archive.log

# 2. A script with a failure as its last statement (exits 1 and stops there)
cat > /tmp/session.sql <<'SQL'
CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
COPY trips FROM 'src/test/resources/trips.csv';
SELECT * FROM trips WHERE distance > 100;
SELECT * FROM trips;
SELECT * FROM trips WHERE city = 'Odense';
SELECT * FROM trips WHERE price < 50.0;
SELECT * FROM missing_table;
SQL
./engine -f /tmp/session.sql

# 3. A second, separately-numbered session
./engine -c "SELECT * FROM trips WHERE distance > 100"