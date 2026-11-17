-- 배포로 서버가 바뀌어도 진행 중인 탑승을 이어 가려고: 새 서버가 이 값으로 탑승을 다시 만든다.
-- phase: WAITING(역에서 기다림) / RIDING(열차 탑승). last_line·last_train_no·last_station과 함께 본다.
ALTER TABLE ride
    ADD COLUMN phase              VARCHAR(10) NULL,
    ADD COLUMN phase_since        DATETIME(3) NULL,
    ADD COLUMN boarded_station_id VARCHAR(20) NULL,
    ADD COLUMN last_contact_at    DATETIME(3) NULL;
