package com.gyejoldanji.domain.image.client.model;

/**
 * 저장소에 실제로 올라간 객체의 메타데이터.
 *
 * @param contentType   저장 시 지정된 Content-Type. 없으면 null
 * @param contentLength 바이트 단위 크기
 */
public record StoredObject(String contentType, long contentLength) {
}
