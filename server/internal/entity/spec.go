package entity

import (
	"fmt"
	"sort"
	"strings"
)

// Spec describes one synced entity type and its table.
type Spec struct {
	Name   string // entity name used in the API; equals the table name
	Fields []Field
	// Check runs cross-field rules on a fully normalised record.
	Check func(r Data) error

	byName map[string]*Field
}

// Data is a record's field values in canonical form.
type Data map[string]any

// ValidationError lists every invalid field of a record.
type ValidationError struct {
	Fields map[string]string
}

func (e *ValidationError) Error() string {
	keys := make([]string, 0, len(e.Fields))
	for k := range e.Fields {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	parts := make([]string, len(keys))
	for i, k := range keys {
		parts[i] = k + ": " + e.Fields[k]
	}
	return strings.Join(parts, "; ")
}

func (s *Spec) init() {
	s.byName = make(map[string]*Field, len(s.Fields))
	for i := range s.Fields {
		f := &s.Fields[i]
		if _, dup := s.byName[f.Name]; dup || reservedColumns[f.Name] {
			panic(fmt.Sprintf("entity %s: duplicate or reserved field %q", s.Name, f.Name))
		}
		s.byName[f.Name] = f
	}
}

// Field returns the field with the given name.
func (s *Spec) Field(name string) (*Field, bool) {
	f, ok := s.byName[name]
	return f, ok
}

// Normalize validates every field in input, applies defaults and runs the
// cross-field check. Unknown fields are ignored for forward compatibility with
// newer clients. The result has a value (possibly nil) for every field.
func (s *Spec) Normalize(input map[string]any) (Data, error) {
	out := make(Data, len(s.Fields))
	errs := map[string]string{}
	for i := range s.Fields {
		f := &s.Fields[i]
		v, err := f.normalize(input[f.Name])
		if err != nil {
			errs[f.Name] = err.Error()
			continue
		}
		if v == nil && f.Default != nil {
			v = f.Default
		}
		if v == nil && f.Required {
			errs[f.Name] = "is required"
			continue
		}
		out[f.Name] = v
	}
	if len(errs) == 0 && s.Check != nil {
		if err := s.Check(out); err != nil {
			if ve, ok := err.(*ValidationError); ok {
				return nil, ve
			}
			errs["_record"] = err.Error()
		}
	}
	if len(errs) > 0 {
		return nil, &ValidationError{Fields: errs}
	}
	return out, nil
}

// NormalizeLenient keeps only the valid fields of input and drops the rest.
// It is used for tombstones, which may carry partial data.
func (s *Spec) NormalizeLenient(input map[string]any) Data {
	out := make(Data, len(s.Fields))
	for i := range s.Fields {
		f := &s.Fields[i]
		v, err := f.normalize(input[f.Name])
		if err != nil || v == nil {
			v = f.Default
		}
		out[f.Name] = v
	}
	return out
}

// Columns returns the entity's field column names in declaration order.
func (s *Spec) Columns() []string {
	cols := make([]string, len(s.Fields))
	for i, f := range s.Fields {
		cols[i] = f.Name
	}
	return cols
}

// DBArgs returns d's values in column order, converted for the database.
func (s *Spec) DBArgs(d Data) []any {
	args := make([]any, len(s.Fields))
	for i := range s.Fields {
		args[i] = s.Fields[i].toDB(d[s.Fields[i].Name])
	}
	return args
}

// FromDB builds Data from scanned values in column order.
func (s *Spec) FromDB(vals []any) Data {
	d := make(Data, len(s.Fields))
	for i := range s.Fields {
		d[s.Fields[i].Name] = s.Fields[i].fromDB(vals[i])
	}
	return d
}

// reservedColumns are the sync columns every entity table has.
var reservedColumns = map[string]bool{
	"ledger_id": true, "id": true, "hlc": true, "seq": true,
	"deleted": true, "server_updated_at": true,
}

var (
	registry []*Spec
	byName   = map[string]*Spec{}
)

func register(s *Spec) {
	s.init()
	registry = append(registry, s)
	byName[s.Name] = s
}

// Lookup returns the spec for an entity name.
func Lookup(name string) (*Spec, bool) {
	s, ok := byName[name]
	return s, ok
}

// All returns every spec in dependency order (referenced types first), which
// is also the order used for exports.
func All() []*Spec { return registry }

// Names returns every entity name in dependency order.
func Names() []string {
	out := make([]string, len(registry))
	for i, s := range registry {
		out[i] = s.Name
	}
	return out
}
