DROP table IF EXISTS light_session;
DROP table IF EXISTS light_session_attributes;


 CREATE TABLE light_session (
    session_id VARCHAR2(100) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval int,
    expiry_time bigint,
    principal_name VARCHAR(100),
    PRIMARY KEY(session_id)
  );


  CREATE TABLE light_session_attributes (
   session_id VARCHAR2(100) NOT NULL,
   attribute_name VARCHAR(200) NOT NULL,
   attribute_bytes BYTEA,
   PRIMARY KEY(session_id, attribute_name)
  );

