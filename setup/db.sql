CREATE USER jping_dev_user WITH PASSWORD 'dev';
CREATE DATABASE jping_dev;
GRANT ALL PRIVILEGES ON DATABASE jping_dev to jping_dev_user;
ALTER ROLE jping_dev_user superuser;

CREATE USER jping_test_user WITH PASSWORD 'test';
CREATE DATABASE jping_test;
GRANT ALL PRIVILEGES ON DATABASE jping_test to jping_test_user;
ALTER ROLE jping_test_user superuser;
