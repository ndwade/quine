import argparse
import json
from pykafka import KafkaClient
import string
import random
from typing import *
import requests
from requests import Response
from termcolor import colored
import logging
import boto3
import time
import pulsar

import gzip
import zlib
import base64

logging.basicConfig(level=logging.INFO)

ENCODINGS = ["Gzip", "Zlib", "Base64"]


class Encoding:

    @classmethod
    def parse_csv(cls, encoding_csv: str):
        encoding_strings = encoding_csv and [s.strip() for s in encoding_csv.split(',')] or []
        return list(filter(lambda e: e in ENCODINGS, encoding_strings))

    @classmethod
    def encode_value(cls, encoding: str, value: Any):
        if encoding == "Gzip":
            return gzip.compress(value)
        elif encoding == "Zlib":
            return zlib.compress(value)
        elif encoding == "Base64":
            return base64.b64encode(value)

    @classmethod
    def decode_value(cls, encoding: str, value: Any):
        if encoding == "Gzip":
            return gzip.decompress(value)
        elif encoding == "Zlib":
            return zlib.decompress(value)
        elif encoding == "Base64":
            return base64.b64decode(value)

    @classmethod
    def encode(cls, encodings: List[str], value: str) ->str:
        bytes = value.encode("utf-8")
        for e in encodings[::-1]:
            bytes = cls.encode_value(e, bytes)
        return bytes.decode("utf-8")

    @classmethod
    def decode(cls, encodings: List[str], value: str) ->str:
        for e in encodings:
            value = cls.decode_value(e, value)
        return value


def random_string(ct: int = 10):
    return ''.join(random.choice(string.ascii_letters) for i in range(ct))


class TestConfig:

    def __init__(self, count: int, quine_url: str, encodings: List[str]):
        self.name = random_string()
        self.quine_url = quine_url
        self.count = count
        self.encodings = encodings

    def recipe(self):
        pass

    def generate_values(self):
        raw_values = [{"test_name": self.name, "counter": i} for i in range(self.count)]
        return list(map(lambda rec: Encoding.encode(self.encodings, json.dumps(rec)), raw_values))

    def write_values(self, values: List[Any]) -> None:
        pass

    def create_recipe(self):
        self.req("post", f'/api/v1/ingest/{self.name}',
                 json=self.recipe() | {
                     "recordDecoders": self.encodings})  # , headers={"Content-type":"application/json"})

    def retrieve_values(self):
        return self.req("post", f'/api/v1/query/cypher/nodes',
                        data=f"MATCH (n) WHERE n.test_name = '{self.name}' RETURN n LIMIT {self.count}",
                        headers={"Content-type": "text/plain"}).json()

    def get_ingested_ct(self):
        rsp = self.req("get", f'/api/v1/ingest/{self.name}').json()
        return rsp["stats"]["ingestedCount"]

    def run_test(self, sleep_time_ms=0):
        # optionally sleep before retrieving values.
        self.create_recipe()
        values = self.generate_values()
        self.write_values(values)
        if sleep_time_ms:
            time.sleep(sleep_time_ms / 1000.0)
        returned_values = self.retrieve_values()
        if (len(returned_values) == self.count):
            print(
                colored(f"Correct number of values ({self.count}) received from type {self.recipe()['type']}", "green"))

        else:
            print(colored(f"Expected {self.count} values, got {len(returned_values)}", "red"))

        for r in returned_values:
            assert(r["properties"]["test_name"] == self.name)

        print(colored(f"returned values are in the correct form: {returned_values[0]}", "green"))
        assert len(returned_values) == self.count

    def req(self, method: str, path: str, **kwargs) -> Optional[Response]:
        url = f'http://{self.quine_url}{path}'
        print(colored(f"call {method} {url} {kwargs}", "blue"))
        response = requests.request(method, f'http://{self.quine_url}{path}', **kwargs)

        if response.ok:
            print(colored(f"Success: {method} {url} {response.status_code}", "green"))
            # logging.debug("%s %s %s", method, url, response.status_code)
            try:
                logging.debug(json.dumps(response.json(), indent=2))
            except:
                pass
        else:
            print(colored(f"Fail: {method} {url} {response.status_code} \n{response._content}", "red"))
            # logging.warning("Failed on %s: %s", url, response.status_code)

        return response


class KinesisConfig(TestConfig):

    def __init__(self, count: int, quine_url: str, stream_name: str, encodings: List[str], creds: Dict[str, str]):
        super().__init__(count, quine_url, encodings)
        self.stream_name = stream_name
        self.creds = creds

    def recipe(self):
        return {"name": self.name,
                "type": "KinesisIngest",
                "format": {"query": "CREATE ($that)", "type": "CypherJson"},
                "streamName": self.stream_name,
                "credentials": {"region": self.creds["region"],
                                "accessKeyId": self.creds["key"],
                                "secretAccessKey": self.creds["secret"]}}

    def write_values(self, values: List[str]):
        kinesis_client = boto3.client('kinesis')
        kinesis_client.put_records(StreamName=self.stream_name,
                                   Records=[{"Data": v, "PartitionKey": "test_name"} for v in values])


class SQSConfig(TestConfig):
    def __init__(self, count: int, quine_url: str, queue_url: str, encodings: List[str], creds: Dict[str, str]):
        super().__init__(count, quine_url, encodings)
        self.queue_url = queue_url
        self.creds = creds

    def recipe(self):
        return {"name": self.name,
                "type": "SQSIngest",
                "format": {"query": "CREATE ($that)", "type": "CypherJson"},
                "queueUrl": self.queue_url,
                "credentials": {"region": self.creds["region"],
                                "accessKeyId": self.creds["key"],
                                "secretAccessKey": self.creds["secret"]}}

    def write_values(self, values: List[str]) -> None:
        sqs_client = boto3.client("sqs", region_name=self.creds["region"])

        for value in values:
            response = sqs_client.send_message(
                QueueUrl=self.queue_url,
                MessageBody=value
            )
            print(f"sent {value} -> {response}")
            logging.debug(response)


class KafkaConfig(TestConfig):

    def __init__(self, count: int, quine_url: str, topic: str, kafka_url: str, commit, encodings: List[str]):
        super().__init__(count, quine_url, encodings)
        self.topic = topic
        self.kafka_url = kafka_url
        self.commit = commit

    def recipe(self):
        return {"name": self.name,
                "type": "KafkaIngest",
                "format": {"query": "CREATE ($that)", "type": "CypherJson"},
                "topics": [self.topic],
                "offsetCommitting": {"type": self.commit},
                "bootstrapServers": self.kafka_url}

    def write_values(self, values: List[str]):
        client = KafkaClient(hosts=self.kafka_url)
        topic = client.topics[self.topic]

        with topic.get_sync_producer() as producer:
            for value in self.generate_values():
                logging.debug(f"writing to {self.topic} [{value}]")
                producer.produce(value.encode("utf-8"))


class PulsarConfig(TestConfig):
    def __init__(self, count: int, quine_url: str, topic: str, pulsar_url: str, subscription_name: str,
                 encodings: List[str]):
        super().__init__(count, quine_url, encodings)
        self.topic = topic
        self.pulsar_url = pulsar_url
        self.subscription_name = subscription_name

    def recipe(self):
        return {"name": self.name,
                "type": "PulsarIngest",
                "format": {"query": "CREATE ($that)", "type": "CypherJson"},
                "topics": [self.topic],
                "pulsarUrl": self.pulsar_url,
                "subscriptionName": self.subscription_name,
                "subscriptionType": "Shared"}

    def write_values(self, values: List[str]):
        client = pulsar.Client(self.pulsar_url)
        producer = client.create_producer(self.topic)

        for value in self.generate_values():
            print(value)
            logging.debug(f"writing to {self.topic} [{value}]")
            producer.send(value.encode("utf-8"))

        client.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        prog="ingest_tester", description="Ingest tests by type"
    )
    parser.add_argument("-q", "--quine_url", default="0.0.0.0:8080", help="quine api url. Default '0.0.0.0:8080'")
    parser.add_argument("-c", "--count", type=int, default=10, help="number of values to send. Default 10")
    parser.add_argument("-e", "--encodings", type=str, help=f"csv list of encodings from {ENCODINGS}")
    subparsers = parser.add_subparsers(dest="type")
    #
    # kafka args
    #
    kafka_parser = subparsers.add_parser("kafka")
    kafka_parser.add_argument(
        "-k", "--kafka_url", default="localhost:9092", help="kafka url. Default 'localhost:9092'"
    )
    kafka_parser.add_argument("-C", "--commit", default="AutoCommit", help="AutoCommit or ExplicitCommit")
    kafka_parser.add_argument("-t", "--topic", help="kafka topic")
    #
    # kinesis args
    #
    kinesis_parser = subparsers.add_parser("kinesis")
    kinesis_parser.add_argument("-n", "--name", help="kinesis stream name", required=True)
    kinesis_parser.add_argument("-r", "--region", help="aws region", default="us-east-1")
    kinesis_parser.add_argument("-k", "--key", help="aws key", required=True)
    kinesis_parser.add_argument("-s", "--secret", help="aws secret", required=True)
    #
    # sqs args
    #
    sqs_parser = subparsers.add_parser("sqs")
    sqs_parser.add_argument("-q", "--queue_url", help="sqs queue url", required=True)
    sqs_parser.add_argument("-r", "--region", help="aws region", default="us-east-1")
    sqs_parser.add_argument("-k", "--key", help="aws key", required=True)
    sqs_parser.add_argument("-s", "--secret", help="aws secret", required=True)
    #
    # pulsar args
    #
    pulsar_parser = subparsers.add_parser("pulsar")
    pulsar_parser.add_argument("-t", "--topic", help="pulsar topic(s) to consumer from", default="test_topic")
    pulsar_parser.add_argument("-u", "--pulsar_url", help="pulsar service url", default="pulsar://localhost:6650")
    pulsar_parser.add_argument("-n", "--subscription_name", help="subscription name", default="my_subscription")

    args = parser.parse_args()

    encodings: List[str] = Encoding.parse_csv(args.encodings)
    if args.type == "kafka":
        config = KafkaConfig(args.count, args.quine_url, args.topic, args.kafka_url, args.commit, encodings)
    elif args.type == "kinesis":
        config = KinesisConfig(args.count, args.quine_url, args.name, encodings,
                               {"region": args.region, "key": args.key, "secret": args.secret})
    elif args.type == "sqs":
        config = SQSConfig(args.count, args.quine_url, args.queue_url, encodings,
                           {"region": args.region, "key": args.key, "secret": args.secret})
    elif args.type == "pulsar":
        config = PulsarConfig(args.count, args.quine_url, args.topic, args.pulsar_url, args.subscription_name,
                              encodings)
    config.run_test(sleep_time_ms=1000)

