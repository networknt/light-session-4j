# light-session


Distributed session managers (Redis, Hazelcast, JDBC) that support web server cluster for light-4j framework.

for redis session manager, user need start redis server before build and test the application"

## Start Redis docker image:

docker run --name some-redis -p 6379:6379 -d redis redis-server --appendonly yes

## Start redis bash:

docker exec -it some-redis bash

## test connection:

/data# redis-cli ping

Result should be: PONG

