// Copyright 2020 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.examples.jetstream;

import io.nats.client.*;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.impl.NatsMessage;
import io.nats.examples.ExampleArgs;
import io.nats.examples.ExampleUtils;

import java.time.Duration;

import static io.nats.examples.ExampleUtils.sleep;
import static io.nats.examples.ExampleUtils.uniqueEnough;
import static io.nats.examples.jetstream.NatsJsUtils.createStream;

/**
 * This example will demonstrate JetStream push subscribing. Run NatsJsPub first to setup message data.
 */
public class NatsJsPushSubFlowControl {
    static final String usageString =
            "\nUsage: java -cp <classpath> NatsJsPushSubFlowControl [-s server]"
                    + "\n\nRun Notes:"
                    + "\n   - THIS EXAMPLE IS NOT INTENDED TO BE CUSTOMIZED. You can still provider the server."
                    + "\n\nUse tls:// or opentls:// to require tls, via the Default SSLContext\n"
                    + "\nSet the environment variable NATS_NKEY to use challenge response authentication by setting a file containing your private key.\n"
                    + "\nSet the environment variable NATS_CREDS to use JWT/NKey authentication by setting a file containing your user creds.\n"
                    + "\nUse the URL for user/pass/token authentication.\n";

    public static void main(String[] args) {
        ExampleArgs exArgs = ExampleArgs.builder().build(args, usageString);
        String stream = "fc-strm-" + uniqueEnough();
        String subject = "fc-sub-" + uniqueEnough();

        try (Connection nc = Nats.connect(ExampleUtils.createExampleOptions(exArgs.server, true))) {

            JetStreamManagement jsm = nc.jetStreamManagement();

            try {
                // creates a memory stream. We will clean it up at the end.
                createStream(jsm, stream, subject);

                // Create our JetStream context to receive JetStream messages.
                JetStream js = nc.jetStream();

                // Set up the consumer configuration to have both flowControl and
                // an idle heartbeat duration
                ConsumerConfiguration cc = ConsumerConfiguration.builder()
                        .flowControl(true)
                        .idleHeartbeat(Duration.ofMillis(250))
                        .build();
                PushSubscribeOptions pso = PushSubscribeOptions.builder().configuration(cc).build();

                // This is configured so the subscriber ends up being considered slow
                JetStreamSubscription sub = js.subscribe(subject, pso);
                nc.flush(Duration.ofSeconds(5));
                sub.setPendingLimits(Consumer.DEFAULT_MAX_MESSAGES, 1024);

                // publish more message data than the subscriber will handle
                byte[] data = new byte[1024];
                for (int x = 1; x <= 100; x++) {
                    Message msg = NatsMessage.builder()
                            .subject(subject)
                            .data(data)
                            .build();
                    js.publish(msg);
                }

                // sleep to let the messages back up
                sleep(1000);

                // what is the status of the subscription
                System.out.println("PendingMessageCount " + sub.getPendingMessageCount() + " PendingByteCount " + sub.getPendingByteCount());

                boolean waitingForFlowControl = true;
                while (waitingForFlowControl) {
                    Message msg = sub.nextMessage(Duration.ofSeconds(1));
                    if (msg != null) {
                        // -------------------------------------------------------------------
                        //  A FLOW CONTROL MESSAGE IS A STATUS MESSAGE
                        // The Status object has a helper method `isFlowControl()`
                        // -------------------------------------------------------------------
                        if (msg.isStatusMessage() && msg.getStatus().isFlowControl()) {
                            System.out.println("STATUS: " + msg.getStatus().getMessage());
                            waitingForFlowControl = false;

                            // -------------------------------------------------------------------
                            // !!!!! WHEN YOU GET A FLOW CONTROL MESSAGE YOU NEED TO RESPOND !!!!!
                            // -------------------------------------------------------------------
                            // Use the regular connection and publish to the flow control
                            // message's reply to as a subject
                            // -------------------------------------------------------------------

                            nc.publish(msg.getReplyTo(), null);

                            // -------------------------------------------------------------------

                        } else if (msg.isJetStream()) {
                            msg.ack();
                        }
                    }
                }

                sub.unsubscribe();
                nc.flush(Duration.ofSeconds(5));
            }
            finally {
                // be a good citizen and remove the example stream
                jsm.deleteStream(stream);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
}
