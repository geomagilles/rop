/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package main

import (
	"context"
	"fmt"
	"os"

	"github.com/apache/rocketmq-client-go/v2"
	"github.com/apache/rocketmq-client-go/v2/primitive"
	"github.com/apache/rocketmq-client-go/v2/producer"
)

func main() {
	p, _ := rocketmq.NewProducer(
		producer.WithNsResolver(primitive.NewPassthroughResolver([]string{"127.0.0.1:9876"})),
		producer.WithRetry(2),
		producer.WithNamespace("test1|InstanceTest"),
	)
	err := p.Start()
	if err != nil {
		fmt.Printf("start producer error: %s", err.Error())
		os.Exit(1)
	}
	tags := []string{"TagA", "TagB", "TagC"}
	for i := 0; i < 3; i++ {
		tag := tags[i%3]
		msg := primitive.NewMessage("test_tag",
			[]byte("Hello RocketMQ Go Client!"))
		msg.WithTag(tag)

		res, err := p.SendSync(context.Background(), msg)
		if err != nil {
			fmt.Printf("send message error: %s\n", err)
		} else {
			fmt.Printf("send message success: result=%s\n", res.String())
		}
	}
	err = p.Shutdown()
	if err != nil {
		fmt.Printf("shutdown producer error: %s", err.Error())
	}
}
