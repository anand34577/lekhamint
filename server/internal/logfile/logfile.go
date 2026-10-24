// Package logfile is a size-rotated log file for installs without a system
// journal (Windows, macOS, plain binaries). When the file passes MaxSize it is
// renamed to name.1 (older copies shift to .2, .3) and a fresh file is started.
package logfile

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
)

// Defaults: 10 MB per file, three old files kept.
const (
	DefaultMaxSize = 10 << 20
	DefaultKeep    = 3
)

// File is an io.Writer safe for concurrent use.
type File struct {
	path    string
	maxSize int64
	keep    int

	mu   sync.Mutex
	f    *os.File
	size int64
}

// Open opens (or creates) path for appending, creating its directory.
func Open(path string, maxSize int64, keep int) (*File, error) {
	if maxSize <= 0 {
		maxSize = DefaultMaxSize
	}
	if keep < 0 {
		keep = DefaultKeep
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return nil, fmt.Errorf("create log directory: %w", err)
	}
	l := &File{path: path, maxSize: maxSize, keep: keep}
	if err := l.open(); err != nil {
		return nil, err
	}
	return l, nil
}

func (l *File) open() error {
	f, err := os.OpenFile(l.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o640)
	if err != nil {
		return fmt.Errorf("open log file: %w", err)
	}
	st, err := f.Stat()
	if err != nil {
		f.Close()
		return err
	}
	l.f, l.size = f, st.Size()
	return nil
}

// Write appends p, rotating first if it would pass the size limit.
func (l *File) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.size > 0 && l.size+int64(len(p)) > l.maxSize {
		if err := l.rotate(); err != nil {
			// Keep logging to the current file rather than losing lines.
			fmt.Fprintln(os.Stderr, "log rotation failed:", err)
		}
	}
	n, err := l.f.Write(p)
	l.size += int64(n)
	return n, err
}

func (l *File) rotate() error {
	if err := l.f.Close(); err != nil {
		return err
	}
	if l.keep == 0 {
		os.Remove(l.path)
	}
	for i := l.keep; i >= 1; i-- {
		src := l.path
		if i > 1 {
			src = fmt.Sprintf("%s.%d", l.path, i-1)
		}
		dst := fmt.Sprintf("%s.%d", l.path, i)
		if _, err := os.Stat(src); err == nil {
			os.Remove(dst) // Windows cannot rename over an existing file
			if err := os.Rename(src, dst); err != nil {
				return l.reopen(err)
			}
		}
	}
	return l.reopen(nil)
}

func (l *File) reopen(cause error) error {
	if err := l.open(); err != nil {
		return err
	}
	return cause
}

// Close closes the file.
func (l *File) Close() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.f.Close()
}
