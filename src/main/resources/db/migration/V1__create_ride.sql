-- 탑승 기록. 판단(탄 열차 찾기)은 메모리의 RideSession이 하고, 여기는 시작·끝·마지막 상태를 남긴다.
CREATE TABLE ride (
    id                   VARCHAR(36)  NOT NULL PRIMARY KEY,
    origin_station       VARCHAR(50)  NOT NULL,
    destination_station  VARCHAR(50)  NULL,
    started_at           DATETIME(3)  NOT NULL,
    ended_at             DATETIME(3)  NULL,
    last_line            VARCHAR(20)  NULL,
    last_train_no        VARCHAR(20)  NULL,
    last_station         VARCHAR(50)  NULL,
    INDEX idx_ride_started_at (started_at)
);
