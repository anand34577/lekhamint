VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
LDFLAGS := -s -w -X main.version=$(VERSION)
DIST    := dist
# Release targets. Binaries are named lekhamint-<os>-<arch>[.exe] with no version,
# so https://github.com/anand34577/lekhamint/releases/latest/download/<name> always works.
TARGETS := linux/amd64 linux/arm64 linux/arm darwin/amd64 darwin/arm64 windows/amd64 windows/arm64 freebsd/amd64

.PHONY: run demo test lint build release web demo-site docker vendor clean

run:            ## run locally with SQLite in ./data
	go run ./cmd/lekhamint serve --allow-signup

demo:           ## run a throwaway server with a year of sample data
	go run ./cmd/lekhamint demo

test:           ## run all tests (set LEKHAMINT_TEST_PG_DSN to also test PostgreSQL)
	go test -count=1 ./...

lint:
	gofmt -l . | (! grep .) && go vet ./...

build:
	CGO_ENABLED=0 go build -trimpath -ldflags "$(LDFLAGS)" -o $(DIST)/lekhamint ./cmd/lekhamint

release:        ## static binaries for every supported OS/arch
	@for target in $(TARGETS); do \
		os=$${target%/*}; arch=$${target#*/}; ext=""; [ $$os = windows ] && ext=".exe"; \
		echo "building $$os/$$arch"; \
		CGO_ENABLED=0 GOOS=$$os GOARCH=$$arch go build -trimpath -ldflags "$(LDFLAGS)" \
			-o $(DIST)/lekhamint-$$os-$$arch$$ext ./cmd/lekhamint || exit 1; \
	done

web:            ## rebuild the browser app (needs Node.js) and embed it in the binary
	cd ../web && npm ci && npm test && npm run build
	rm -rf internal/webui/dist && cp -r ../web/dist internal/webui/dist

demo-site:      ## the online demo: web app + this server compiled to WebAssembly (../web/dist-demo)
	cd ../web && npm ci && npm run build:demo
	GOOS=js GOARCH=wasm go build -trimpath -ldflags "$(LDFLAGS)" -o ../web/dist-demo/lekhamint.wasm ./cmd/lekhamint-wasm
	cp "$$(go env GOROOT)/lib/wasm/wasm_exec.js" ../web/dist-demo/

docker:         ## build the container image (from the repository root)
	docker build --build-arg VERSION=$(VERSION) -t lekhamint:$(VERSION) ..

vendor:         ## copy dependencies into ./vendor for fully offline builds
	go mod vendor

clean:
	rm -rf $(DIST)
