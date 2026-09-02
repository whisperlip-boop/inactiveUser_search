package co.bskim.jira.inactiveuser.web;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;

/**
 * 하위 필터/서블릿이 쓴 응답 본문을 메모리에 받아두는 래퍼. 받아둔 내용을 고쳐서 진짜 응답에
 * 다시 써야 하므로 Content-Length는 여기서 무시하고 최종 시점에 다시 계산한다.
 */
class CapturingResponseWrapper extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private ServletOutputStream outputStream;
    private PrintWriter writer;

    CapturingResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    @Override
    public ServletOutputStream getOutputStream() {
        if (writer != null) {
            throw new IllegalStateException("getWriter()를 이미 호출했다");
        }
        if (outputStream == null) {
            outputStream = new ServletOutputStream() {
                @Override
                public void write(int b) {
                    buffer.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    buffer.write(b, off, len);
                }

                // Servlet 3.1 비동기 쓰기 API. 우리는 메모리 버퍼라 항상 쓸 준비가 돼 있다.
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener writeListener) {
                    // 논블로킹 쓰기를 지원하지 않는다. 이 래퍼는 동기 경로에서만 쓰인다.
                }
            };
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws UnsupportedEncodingException {
        if (outputStream != null) {
            throw new IllegalStateException("getOutputStream()을 이미 호출했다");
        }
        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(buffer, charset()));
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        if (outputStream != null) {
            outputStream.flush();
        }
        // 진짜 응답으로는 흘려보내지 않는다. 커밋되면 본문을 고칠 수 없다.
    }

    /** 본문 길이를 우리가 다시 정하므로 하위에서 설정한 값은 버린다. */
    @Override
    public void setContentLength(int len) {
        // 무시
    }

    @Override
    public void setContentLengthLong(long len) {
        // 무시
    }

    String charset() {
        String encoding = getCharacterEncoding();
        return (encoding == null || encoding.isEmpty()) ? "UTF-8" : encoding;
    }

    byte[] captured() {
        if (writer != null) {
            writer.flush();
        }
        return buffer.toByteArray();
    }
}
