DROP table IF EXISTS light_session;
DROP table IF EXISTS light_session_attributes;


 CREATE TABLE light_session (
    session_id VARCHAR2(100) NOT NULL,
    creation_time NUMBER NOT NULL,
    last_access_time NUMBER NOT NULL,
    max_inactive_interval int,
    expiry_time NUMBER,
    principal_name VARCHAR(100)
  );

  ALTER TABLE light_session ADD ( CONSTRAINT PK_LIGHT_SESSION  PRIMARY KEY (session_id) ) ;



  CREATE TABLE light_session_attributes (
   session_id VARCHAR2(100) NOT NULL,
   attribute_name VARCHAR(200) NOT NULL,
   attribute_bytes blob(1M)
  );

   ALTER TABLE light_session_attributes ADD ( CONSTRAINT PK_LIGHT_SESSION_ATTRIBUTES  PRIMARY KEY (session_id, attribute_name) ) ;

