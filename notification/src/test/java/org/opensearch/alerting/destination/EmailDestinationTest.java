/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.alerting.destination;

import org.opensearch.alerting.destination.factory.DestinationFactoryProvider;
import org.opensearch.alerting.destination.factory.EmailDestinationFactory;
import org.opensearch.alerting.destination.message.BaseMessage;
import org.opensearch.alerting.destination.message.DestinationType;
import org.opensearch.alerting.destination.message.EmailMessage;
import org.opensearch.alerting.destination.client.DestinationEmailClient;
import org.opensearch.alerting.destination.response.DestinationResponse;
import org.easymock.EasyMock;
import org.opensearch.common.settings.SecureString;
import org.junit.Assert;
import org.junit.Test;
import javax.mail.Message;
import javax.mail.MessagingException;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;

public class EmailDestinationTest {

    @Test
    public void testMailMessage() throws Exception {

        DestinationResponse expectedEmailResponse = new DestinationResponse.Builder()
                .withResponseContent("Sent")
                .withStatusCode(0).build();

        DestinationEmailClient emailClient = EasyMock.partialMockBuilder(DestinationEmailClient.class)
                .addMockedMethod("SendMessage").createMock();
        emailClient.SendMessage(EasyMock.anyObject(Message.class));

        EmailDestinationFactory emailDestinationFactory = new EmailDestinationFactory();
        emailDestinationFactory.setClient(emailClient);

        DestinationFactoryProvider.setFactory(DestinationType.EMAIL, emailDestinationFactory);

        String message = "{\"text\":\"Vamshi Message gughjhjlkh Body emoji test: :) :+1: " +
                "link test: http://sample.com email test: marymajor@example.com All member callout: " +
                "@All All Present member callout: @Present\"}";
        BaseMessage bm = new EmailMessage.Builder("abc")
                .withMessage(message)
                .withHost("abc.com")
                .withPort(465)
                .withFrom("test@abc.com")
                .withMethod("ssl")
                .withSubject("Test")
                .withMessage("Test alert")
                .withRecipients(singletonList("test@abc.com")).build();

        DestinationResponse actualEmailResponse = (DestinationResponse) Notification.publish(bm);
        assertEquals(expectedEmailResponse.getResponseContent(), actualEmailResponse.getResponseContent());
        assertEquals(expectedEmailResponse.getStatusCode(), actualEmailResponse.getStatusCode());
    }

    @Test
    public void testMailMessageWithAuth() throws Exception {

        DestinationResponse expectedEmailResponse = new DestinationResponse.Builder()
                .withResponseContent("Sent")
                .withStatusCode(0).build();

        DestinationEmailClient emailClient = EasyMock.partialMockBuilder(DestinationEmailClient.class)
                .addMockedMethod("SendMessage").createMock();
        emailClient.SendMessage(EasyMock.anyObject(Message.class));

        EmailDestinationFactory emailDestinationFactory = new EmailDestinationFactory();
        emailDestinationFactory.setClient(emailClient);

        DestinationFactoryProvider.setFactory(DestinationType.EMAIL, emailDestinationFactory);

        String message = "{\"text\":\"Vamshi Message gughjhjlkh Body emoji test: :) :+1: " +
                "link test: http://sample.com email test: marymajor@example.com All member callout: " +
                "@All All Present member callout: @Present\"}";
        SecureString username = new SecureString("user1".toCharArray());
        SecureString password = new SecureString("password".toCharArray());
        BaseMessage bm = new EmailMessage.Builder("abc")
                .withMessage(message)
                .withHost("abc.com")
                .withPort(465)
                .withFrom("test@abc.com")
                .withMethod("ssl")
                .withSubject("Test")
                .withMessage("Test alert")
                .withUserName(username)
                .withPassword(password)
                .withRecipients(singletonList("test@abc.com")).build();

        DestinationResponse actualEmailResponse = (DestinationResponse) Notification.publish(bm);
        assertEquals(expectedEmailResponse.getResponseContent(), actualEmailResponse.getResponseContent());
        assertEquals(expectedEmailResponse.getStatusCode(), actualEmailResponse.getStatusCode());
    }

    @Test(expected = IllegalStateException.class)
    public void testFailingMailMessage() throws Exception {

        DestinationResponse expectedEmailResponse = new DestinationResponse.Builder()
                .withResponseContent("Couldn't connect to host, port: localhost, 55555; timeout -1")
                .withStatusCode(1).build();

        DestinationEmailClient emailClient = EasyMock.partialMockBuilder(DestinationEmailClient.class)
                .addMockedMethod("SendMessage").createMock();
        emailClient.SendMessage(EasyMock.anyObject(Message.class));
        EasyMock.expectLastCall()
                .andThrow(new MessagingException("Couldn't connect to host, port: localhost, 55555; timeout -1"));
        EasyMock.replay(emailClient);

        EmailDestinationFactory emailDestinationFactory = new EmailDestinationFactory();
        emailDestinationFactory.setClient(emailClient);

        DestinationFactoryProvider.setFactory(DestinationType.EMAIL, emailDestinationFactory);

        String message = "{\"text\":\"Vamshi Message gughjhjlkh Body emoji test: :) :+1: " +
                "link test: http://sample.com email test: marymajor@example.com All member callout: " +
                "@All All Present member callout: @Present\"}";
        BaseMessage bm = new EmailMessage.Builder("abc")
                .withMessage(message)
                .withHost("localhost")
                .withPort(55555)
                .withFrom("test@abc.com")
                .withRecipients(singletonList("test@abc.com")).build();

        DestinationResponse actualEmailResponse = (DestinationResponse) Notification.publish(bm);
        EasyMock.verify(emailClient);
        assertEquals(expectedEmailResponse.getResponseContent(), actualEmailResponse.getResponseContent());
        assertEquals(expectedEmailResponse.getStatusCode(), actualEmailResponse.getStatusCode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHostMissingMessage() {
        try {
            EmailMessage message = new EmailMessage.Builder("mail")
                    .withMessage("dummyMessage")
                    .withFrom("test@abc.com")
                    .withRecipients(singletonList("test@abc.com")).build();

        } catch (Exception ex) {
            Assert.assertEquals("Host name should be provided", ex.getMessage());
            throw ex;
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testContentMissingMessage() {
        try {
            EmailMessage message = new EmailMessage.Builder("mail")
                    .withHost("abc.com")
                    .withFrom("test@abc.com")
                    .withRecipients(singletonList("test@abc.com")).build();
        } catch (Exception ex) {
            assertEquals("Message content is missing", ex.getMessage());
            throw ex;
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromMissingMessage() {
        try {
            EmailMessage message = new EmailMessage.Builder("mail")
                    .withMessage("dummyMessage")
                    .withHost("abc.com")
                    .withRecipients(singletonList("test@abc.com")).build();

        } catch (Exception ex) {
            Assert.assertEquals("From address should be provided", ex.getMessage());
            throw ex;
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRecipientsMissingMessage() {
        try {
            EmailMessage message = new EmailMessage.Builder("mail")
                    .withMessage("dummyMessage")
                    .withHost("abc.com")
                    .withFrom("test@abc.com").build();

        } catch (Exception ex) {
            Assert.assertEquals("List of recipients should be provided", ex.getMessage());
            throw ex;
        }
    }
}
