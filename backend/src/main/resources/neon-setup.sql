-- Run this in your Neon SQL Editor (Console -> SQL Editor) before starting the app:

-- 1. Enable pgvector extension for AI embeddings
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Enable UUID generator support
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 3. Enable hstore extension
CREATE EXTENSION IF NOT EXISTS hstore;
