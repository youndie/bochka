// A command-line S3 client on minio-go, for ci/live-clients.sh.
//
// It stands where `mc` stood, and for the same reason: minio-go is the library `mc` was built on,
// and over plain HTTP it signs a PUT body chunk by chunk — STREAMING-AWS4-HMAC-SHA256-PAYLOAD — which
// no other client in the harness sends. `mc` itself is gone from both registries MinIO published it
// to, so the harness builds the same behaviour from source, pinned in go.sum, instead of pulling it.
//
//	s3mg <endpoint> put <bucket> <key> <file>
//	s3mg <endpoint> get <bucket> <key> <file>
//	s3mg <endpoint> ls <bucket>
//
// Credentials come from AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY, as for every other client here.
package main

import (
	"context"
	"fmt"
	"net/url"
	"os"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "s3mg:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	if len(args) < 3 {
		return fmt.Errorf("usage: s3mg <endpoint> put|get|ls <bucket> [key file]")
	}
	endpoint, err := url.Parse(args[0])
	if err != nil {
		return err
	}
	client, err := minio.New(endpoint.Host, &minio.Options{
		Creds:        credentials.NewStaticV4(os.Getenv("AWS_ACCESS_KEY_ID"), os.Getenv("AWS_SECRET_ACCESS_KEY"), ""),
		Secure:       endpoint.Scheme == "https",
		Region:       "us-east-1",
		BucketLookup: minio.BucketLookupPath,
		// Left false on purpose. With trailing headers minio-go moves its checksum into a trailer and
		// the framing becomes STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER, which is not the one this
		// client is here to send.
		TrailingHeaders: false,
	})
	if err != nil {
		return err
	}
	ctx := context.Background()
	bucket := args[2]

	switch args[1] {
	case "put":
		if len(args) != 5 {
			return fmt.Errorf("usage: s3mg <endpoint> put <bucket> <key> <file>")
		}
		_, err = client.FPutObject(ctx, bucket, args[3], args[4], minio.PutObjectOptions{})
		return err
	case "get":
		if len(args) != 5 {
			return fmt.Errorf("usage: s3mg <endpoint> get <bucket> <key> <file>")
		}
		return client.FGetObject(ctx, bucket, args[3], args[4], minio.GetObjectOptions{})
	case "ls":
		for object := range client.ListObjects(ctx, bucket, minio.ListObjectsOptions{Recursive: true}) {
			if object.Err != nil {
				return object.Err
			}
			fmt.Println(object.Key)
		}
		return nil
	default:
		return fmt.Errorf("unknown command %q", args[1])
	}
}
