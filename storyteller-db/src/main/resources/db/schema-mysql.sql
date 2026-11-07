CREATE TABLE story_session (
  session_id VARCHAR(36) NOT NULL,
  title VARCHAR(255),
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  last_accessed_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  infinite BOOLEAN DEFAULT FALSE NOT NULL,
  PRIMARY KEY (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE session_configuration (
  session_id VARCHAR(36) NOT NULL,
  temperature DECIMAL(4, 3),
  top_p DECIMAL(4, 3),
  validation_enabled BOOLEAN,
  streaming_enabled BOOLEAN,
  graph_enabled BOOLEAN,
  max_tokens INTEGER,
  PRIMARY KEY (session_id),
  FOREIGN KEY (session_id) REFERENCES story_session (session_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE session_prompt_override (
  session_id VARCHAR(36) NOT NULL,
  override_name VARCHAR(64) NOT NULL,
  override_content LONGTEXT NOT NULL,
  PRIMARY KEY (session_id, override_name),
  FOREIGN KEY (session_id) REFERENCES story_session (session_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE story_message (
  session_id VARCHAR(36) NOT NULL,
  message_index INTEGER NOT NULL,
  message_role VARCHAR(16) NOT NULL,
  content LONGTEXT NOT NULL,
  image_media_type VARCHAR(64),
  image_content LONGBLOB,
  PRIMARY KEY (session_id, message_index),
  FOREIGN KEY (session_id) REFERENCES story_session (session_id) ON DELETE CASCADE,
  CHECK (message_index >= 0),
  CHECK (message_role IN ('system', 'user', 'assistant', 'tool'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE session_memory (
  session_id VARCHAR(36) NOT NULL,
  summary_content LONGTEXT,
  recent_summary_content LONGTEXT,
  canonical_state_content LONGTEXT,
  summary_cursor INTEGER NOT NULL,
  recent_summary_cursor INTEGER NOT NULL,
  canonical_state_cursor INTEGER NOT NULL,
  PRIMARY KEY (session_id),
  FOREIGN KEY (session_id) REFERENCES story_session (session_id) ON DELETE CASCADE,
  CHECK (summary_cursor >= 0),
  CHECK (recent_summary_cursor >= 0),
  CHECK (canonical_state_cursor >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE turn_state (
  session_id VARCHAR(36) NOT NULL,
  trigger_word VARCHAR(255) NOT NULL,
  started BOOLEAN NOT NULL,
  round_number INTEGER NOT NULL,
  PRIMARY KEY (session_id),
  FOREIGN KEY (session_id) REFERENCES story_session (session_id) ON DELETE CASCADE,
  CHECK (round_number >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE turn_protagonist (
  session_id VARCHAR(36) NOT NULL,
  protagonist_index INTEGER NOT NULL,
  protagonist_name VARCHAR(255) NOT NULL,
  turns_this_round INTEGER NOT NULL,
  PRIMARY KEY (session_id, protagonist_index),
  FOREIGN KEY (session_id) REFERENCES turn_state (session_id) ON DELETE CASCADE,
  CHECK (protagonist_index >= 0),
  CHECK (turns_this_round >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE knowledge_graph (
  session_id VARCHAR(36) NOT NULL,
  schema_version INTEGER NOT NULL,
  revision BIGINT NOT NULL,
  PRIMARY KEY (session_id),
  FOREIGN KEY (session_id) REFERENCES story_session (session_id) ON DELETE CASCADE,
  CHECK (schema_version > 0),
  CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE knowledge_entity (
  session_id VARCHAR(36) NOT NULL,
  entity_id VARCHAR(255) NOT NULL,
  entity_type VARCHAR(64) NOT NULL,
  entity_name VARCHAR(255) NOT NULL,
  entity_source VARCHAR(64) NOT NULL,
  PRIMARY KEY (session_id, entity_id),
  FOREIGN KEY (session_id) REFERENCES knowledge_graph (session_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE knowledge_entity_alias (
  session_id VARCHAR(36) NOT NULL,
  entity_id VARCHAR(255) NOT NULL,
  alias_index INTEGER NOT NULL,
  alias_name VARCHAR(255) NOT NULL,
  PRIMARY KEY (session_id, entity_id, alias_index),
  FOREIGN KEY (session_id, entity_id)
    REFERENCES knowledge_entity (session_id, entity_id) ON DELETE CASCADE,
  CHECK (alias_index >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE knowledge_fact (
  session_id VARCHAR(36) NOT NULL,
  fact_id VARCHAR(255) NOT NULL,
  subject_entity_id VARCHAR(255) NOT NULL,
  predicate_id VARCHAR(255) NOT NULL,
  object_entity_id VARCHAR(255) NOT NULL,
  polarity VARCHAR(32) NOT NULL,
  fact_status VARCHAR(32) NOT NULL,
  fact_source VARCHAR(64) NOT NULL,
  source_turn INTEGER,
  is_hard BOOLEAN NOT NULL,
  PRIMARY KEY (session_id, fact_id),
  FOREIGN KEY (session_id) REFERENCES knowledge_graph (session_id) ON DELETE CASCADE,
  FOREIGN KEY (session_id, subject_entity_id)
    REFERENCES knowledge_entity (session_id, entity_id),
  FOREIGN KEY (session_id, object_entity_id)
    REFERENCES knowledge_entity (session_id, entity_id),
  CHECK (source_turn IS NULL OR source_turn >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
