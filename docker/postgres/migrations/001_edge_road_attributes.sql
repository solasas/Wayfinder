-- Adds the road-attribute columns used by intent-based routing to an EXISTING database.
-- Idempotent and non-destructive: safe to run repeatedly; existing rows get NULL (= unknown).
--
-- Fresh databases get these columns from docker/postgres/init.sql. Existing databases also
-- get them automatically at app start (spring.jpa.hibernate.ddl-auto=update adds missing
-- nullable columns); run this script manually if you run with ddl-auto=validate/none.
--
-- The columns can't be back-filled from the data already in the table. Populate them with:
--   ./mvnw spring-boot:run -Dspring-boot.run.profiles=import -Dspring-boot.run.arguments=--force-reimport
-- Until then every hard constraint is unverifiable (the API says so rather than guessing).
ALTER TABLE edges ADD COLUMN IF NOT EXISTS highway VARCHAR(32);
ALTER TABLE edges ADD COLUMN IF NOT EXISTS toll    BOOLEAN;
ALTER TABLE edges ADD COLUMN IF NOT EXISTS lit     BOOLEAN;
ALTER TABLE edges ADD COLUMN IF NOT EXISTS paved   BOOLEAN;
