-- 앱 설정 화면의 문의하기. 답장받을 주소는 사용자가 적었을 때만(선택). 1년이 지나면 지운다(개인정보처리방침).
CREATE TABLE feedback
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    created_at  DATETIME(3)   NOT NULL,
    message     VARCHAR(2000) NOT NULL,
    contact     VARCHAR(200)  NULL,
    platform    VARCHAR(20)   NULL,
    app_version VARCHAR(40)   NULL,
    device      VARCHAR(100)  NULL,
    INDEX idx_feedback_created_at (created_at)
);
