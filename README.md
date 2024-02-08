# light-session

[Stack Overflow](https://stackoverflow.com/questions/tagged/light-4j) |
[Google Group](https://groups.google.com/forum/#!forum/light-4j) |
[Gitter Chat](https://gitter.im/networknt/light-4j) |
[Subreddit](https://www.reddit.com/r/lightapi/) |
[Youtube Channel](https://www.youtube.com/channel/UCHCRMWJVXw8iB7zKxF55Byw) |
[Documentation](https://doc.networknt.com/style/light-session-4j/) |
[Contribution Guide](https://doc.networknt.com/contribute/) |

light-4j framework build upon undertow server. Undertow manager session in single node module and doesn't support distributed session management.

Light-session aims to provide a common infrastructure for managing sessions in distributed environment.

Light-session provide different types of repository for distributed session management which include Hazelcast, JDBD and Redis.

## Project module:

session-core           --- core components and interfaces for the session management. It should be included any type of repository. And this module also provide in memory session manager for single node.


hazelcast-mananger     --- use hazelcast as repository for session management. Session will be persisted in the hazelcast distributed cache repository.

jdbc-manager           --- use RDBMS database as repository for session management. Session will be persisted in the database tables.

redis-manager          --- use redis as repository for session management. Session will be persisted in the redis in-memory data structure store.


## In memory session manager:

In memory session manager provided in the session-core module. System use the Caffeine as in memory cache to store the session:

https://github.com/ben-manes/caffeine

In server.yml file:

```
- com.networknt.session.SessionManager:
  - com.networknt.session.MapSessionManager
```


## Hazelcast session manager

 System use the Hazelcast as distributed cache repository to store the session:

In server.yml file:

```
- com.networknt.session.SessionManager:
  - com.networknt.session.hazelcast.HazelcastSessionManager
```


## JDBC session manager

System provide two set of DDL script, one for Oracle, another for postgres. User need create the tables in the database before use the session management:

In server.yml file:

```
- com.networknt.session.SessionManager:
   - com.networknt.session.jdbc.JdbcSessionManager
```


Below is the sample for the script:

```
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
```



## Redis session manager

In server.yml file:

```
- com.networknt.session.SessionManager:
  - com.networknt.session.redis.RedisSessionManager
```

User need start redis server before build and test the application

## Start Redis docker image:

docker run --name some-redis -p 6379:6379 -d redis redis-server --appendonly yes

## Start redis bash:

docker exec -it some-redis bash

## test connection:

/data# redis-cli ping

Result should be: PONG
