VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
LDFLAGS := -s -w -X main.version=$(VERSION)
DIST    := dist

.PHONY: run test lint build release docker vendor clean web

run:            ## run locally with SQLite in ./data
	go run ./cmd/kosh serve --allow-signup

test:           ## run all tests (set KOSH_TEST_PG_DSN to also test PostgreSQL)
	go test -count=1 ./...

lint:
	gofmt -l . | (! grep .) && go vet ./...

build:
	CGO_ENABLED=0 go build -trimpath -ldflags "$(LDFLAGS)" -o $(DIST)/kosh ./cmd/kosh

release:        ## static binaries for every supported OS/arch
	@for target in linux/amd64 linux/arm64 linux/arm darwin/amd64 darwin/arm64 windows/amd64 windows/arm64 freebsd/amd64; do \
		os=$${target%/*}; arch=$${target#*/}; ext=""; [ $$os = windows ] && ext=".exe"; \
		echo "building $$os/$$arch"; \
		CGO_ENABLED=0 GOOS=$$os GOARCH=$$arch go build -trimpath -ldflags "$(LDFLAGS)" \
			-o $(DIST)/kosh-$(VERSION)-$$os-$$arch$$ext ./cmd/kosh || exit 1; \
	done

docker:
	docker build --build-arg VERSION=$(VERSION) -t kosh:$(VERSION) .

web:            ## rebuild the browser app (needs Node.js) and embed it in the binary
	cd ../web && npm ci && npm test && npm run build
	rm -rf internal/webui/dist && cp -r ../web/dist internal/webui/dist

vendor:         ## copy dependencies into ./vendor for fully offline builds
	go mod vendor

clean:
	rm -rf $(DIST)
