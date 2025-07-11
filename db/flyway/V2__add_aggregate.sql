CREATE TABLE dict.remote_dictionary (
    REMOTE_DICTIONARY_ID SERIAL PRIMARY KEY,
    NAME CHARACTER VARYING(512) NOT NULL,
    UUID UUID NOT NULL,
    LAST_UPDATED TIMESTAMP
);

CREATE TABLE dict.concept_node__remote_dictionary (
    CONCEPT_NODE_ID integer NOT NULL,
    REMOTE_DICTIONARY_ID integer NOT NULL,
    CONSTRAINT fk_remote_dictionary FOREIGN KEY (REMOTE_DICTIONARY_ID) REFERENCES dict.remote_dictionary(REMOTE_DICTIONARY_ID),
    CONSTRAINT fk_concept_node FOREIGN KEY (CONCEPT_NODE_ID) REFERENCES dict.concept_node(CONCEPT_NODE_ID)
);