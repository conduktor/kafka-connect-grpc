package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"sync/atomic"
	"syscall"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/health"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/reflection"
)

// Server implements TestStreamServiceServer
type Server struct {
	UnimplementedTestStreamServiceServer
	sequence atomic.Int64
}

// StreamEvents implements server streaming RPC
func (s *Server) StreamEvents(req *StreamRequest, stream TestStreamService_StreamEventsServer) error {
	log.Printf("New stream connection - filter: %q, interval: %dms", req.Filter, req.IntervalMs)

	interval := time.Duration(req.IntervalMs) * time.Millisecond
	if interval <= 0 {
		interval = 1000 * time.Millisecond
	}

	eventTypes := []string{"user.created", "order.placed", "payment.processed", "item.shipped", "user.login"}

	for {
		seq := s.sequence.Add(1)
		eventType := eventTypes[seq%int64(len(eventTypes))]

		// Apply filter if specified
		if req.Filter != "" && req.Filter != eventType {
			time.Sleep(interval)
			continue
		}

		// Create payload
		payload := map[string]interface{}{
			"userId":    fmt.Sprintf("user-%d", seq%100),
			"orderId":   fmt.Sprintf("order-%d", seq),
			"amount":    float64(seq%1000) + 0.99,
			"currency":  "USD",
			"processed": true,
		}
		payloadJSON, _ := json.Marshal(payload)

		event := &StreamEvent{
			EventId:   fmt.Sprintf("evt-%d-%d", time.Now().UnixNano(), seq),
			Timestamp: time.Now().UTC().Format(time.RFC3339Nano),
			EventType: eventType,
			Payload:   string(payloadJSON),
			Sequence:  seq,
		}

		if err := stream.Send(event); err != nil {
			log.Printf("Stream closed: %v", err)
			return err
		}

		log.Printf("Sent event #%d: %s", seq, eventType)
		time.Sleep(interval)
	}
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "50051"
	}

	lis, err := net.Listen("tcp", ":"+port)
	if err != nil {
		log.Fatalf("Failed to listen: %v", err)
	}

	grpcServer := grpc.NewServer()

	// Register our streaming service
	RegisterTestStreamServiceServer(grpcServer, &Server{})

	// Register health check
	healthServer := health.NewServer()
	healthpb.RegisterHealthServer(grpcServer, healthServer)
	healthServer.SetServingStatus("", healthpb.HealthCheckResponse_SERVING)
	healthServer.SetServingStatus("teststream.TestStreamService", healthpb.HealthCheckResponse_SERVING)

	// Enable reflection for grpcurl
	reflection.Register(grpcServer)

	// Graceful shutdown
	go func() {
		sigCh := make(chan os.Signal, 1)
		signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
		<-sigCh
		log.Println("Shutting down...")
		grpcServer.GracefulStop()
	}()

	log.Printf("gRPC test server listening on :%s", port)
	log.Printf("Service: teststream.TestStreamService")
	log.Printf("Method: StreamEvents (server streaming)")
	if err := grpcServer.Serve(lis); err != nil {
		log.Fatalf("Failed to serve: %v", err)
	}
}
