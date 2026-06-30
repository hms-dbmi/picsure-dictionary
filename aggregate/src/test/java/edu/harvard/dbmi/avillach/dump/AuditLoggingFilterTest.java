package edu.harvard.dbmi.avillach.dump;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuditLoggingFilterTest {

    private LoggingEvent runFilterWith(MockHttpServletRequest request) throws Exception {
        LoggingClient loggingClient = mock(LoggingClient.class);
        when(loggingClient.isEnabled()).thenReturn(true);

        AuditLoggingFilter filter = new AuditLoggingFilter(loggingClient, "10.0.0.1", 8080);
        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void recordsCallerFromXClientTypeHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dump/concepts");
        request.addHeader("X-Client-Type", "PYTHON_ADAPTER");

        LoggingEvent event = runFilterWith(request);

        assertEquals("PYTHON_ADAPTER", event.getMetadata().get("caller"));
    }

    @Test
    void omitsCallerWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dump/concepts");

        LoggingEvent event = runFilterWith(request);

        assertFalse(event.getMetadata().containsKey("caller"));
    }
}
