-- ============================================================================
-- V4__source_config_object_check.sql
-- Constrain source.config to a JSON *object*.
--
-- WHY: "schemaless" is not the same as "unconstrained". The column is
-- JSONB NOT NULL DEFAULT '{}' (V1), which stops NULL but happily accepts
-- '[]', '"rss"', '42' or the JSON literal 'null' -- all valid JSONB, none of
-- them a config map. Every one of those makes the app-side parse fail at crawl
-- time, on a schedule, in a log nobody is reading. A CHECK moves that failure
-- to the write that caused it.
--
-- This is the general shape of the JSONB tradeoff: the database stops enforcing
-- the *inside* of the document, so you pay it back with (a) a constraint on the
-- envelope here, and (b) a typed parse with tolerant defaults in the app
-- (SourceConfig). Neither alone is enough.
--
-- LOCKING NOTE: ADD CONSTRAINT ... CHECK takes ACCESS EXCLUSIVE and scans the
-- whole table to validate existing rows. Irrelevant on a table with a handful
-- of sources. On a large hot table the pattern is two steps --
--     ALTER TABLE t ADD CONSTRAINT c CHECK (...) NOT VALID;   -- brief lock
--     ALTER TABLE t VALIDATE CONSTRAINT c;                    -- SHARE UPDATE EXCLUSIVE
-- -- which lets writes continue during the scan. Worth knowing before the first
-- time you need it on `article`.
-- ============================================================================

ALTER TABLE source
    ADD CONSTRAINT source_config_object_ck
    CHECK (jsonb_typeof(config) = 'object');
