// Package entity defines the synced record types (accounts, transactions, …)
// as declarative specs. A spec drives input validation, normalisation, SQL
// column lists and database value conversion, so adding a field is a
// one-line change plus a migration.
package entity

import (
	"encoding/json"
	"fmt"
	"math"
	"regexp"
	"slices"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/anand34577/lekhamint/server/internal/ids"
)

// Kind is a field's logical type.
type Kind int

const (
	KString   Kind = iota // single-line text, trimmed
	KText                 // multi-line text
	KInt                  // int64
	KBool                 // stored as 0/1
	KEnum                 // one of Field.Enum
	KRef                  // UUID of another record in the same ledger
	KDate                 // YYYY-MM-DD
	KCurrency             // ISO 4217 or custom code: [A-Z0-9]{3,10}
	KColor                // #RRGGBB
	KDecimal              // positive decimal string, e.g. "83.1245"
	KIDList               // JSON array of UUIDs, stored as TEXT
)

// Field describes one entity attribute.
type Field struct {
	Name     string
	Kind     Kind
	Required bool     // must be present and non-null
	Default  any      // used when absent or null (makes the field non-nullable)
	MaxLen   int      // max runes for strings, max items for lists
	Min, Max *int64   // inclusive bounds for KInt
	Enum     []string // allowed values for KEnum
}

func i64(v int64) *int64 { return &v }

var (
	currencyRe = regexp.MustCompile(`^[A-Z0-9]{3,10}$`)
	colorRe    = regexp.MustCompile(`^#[0-9A-Fa-f]{6}$`)
	decimalRe  = regexp.MustCompile(`^[0-9]{1,18}(\.[0-9]{1,18})?$`)
)

// normalize converts a JSON-decoded value into its canonical Go form:
// string, int64, bool, []string or nil.
func (f *Field) normalize(v any) (any, error) {
	if v == nil {
		return nil, nil
	}
	switch f.Kind {
	case KString, KText:
		s, ok := v.(string)
		if !ok {
			return nil, errType("string")
		}
		if f.Kind == KString {
			s = strings.TrimSpace(s)
			if strings.ContainsAny(s, "\r\n") {
				return nil, fmt.Errorf("must be a single line")
			}
		}
		if !utf8.ValidString(s) {
			return nil, fmt.Errorf("must be valid UTF-8")
		}
		if s == "" {
			return nil, nil // empty optional text is stored as null
		}
		if f.MaxLen > 0 && utf8.RuneCountInString(s) > f.MaxLen {
			return nil, fmt.Errorf("must be at most %d characters", f.MaxLen)
		}
		return s, nil
	case KInt:
		n, err := toInt64(v)
		if err != nil {
			return nil, err
		}
		if f.Min != nil && n < *f.Min {
			return nil, fmt.Errorf("must be >= %d", *f.Min)
		}
		if f.Max != nil && n > *f.Max {
			return nil, fmt.Errorf("must be <= %d", *f.Max)
		}
		return n, nil
	case KBool:
		b, ok := v.(bool)
		if !ok {
			return nil, errType("boolean")
		}
		return b, nil
	case KEnum:
		s, ok := v.(string)
		if !ok {
			return nil, errType("string")
		}
		if !slices.Contains(f.Enum, s) {
			return nil, fmt.Errorf("must be one of %s", strings.Join(f.Enum, ", "))
		}
		return s, nil
	case KRef:
		s, ok := v.(string)
		if !ok {
			return nil, errType("UUID string")
		}
		n, ok := ids.Normalize(s)
		if !ok {
			return nil, fmt.Errorf("must be a UUID")
		}
		return n, nil
	case KDate:
		s, ok := v.(string)
		if !ok {
			return nil, errType("date string")
		}
		if _, err := time.Parse(time.DateOnly, s); err != nil || len(s) != 10 {
			return nil, fmt.Errorf("must be a date in YYYY-MM-DD form")
		}
		return s, nil
	case KCurrency:
		s, ok := v.(string)
		if !ok || !currencyRe.MatchString(s) {
			return nil, fmt.Errorf("must be a currency code such as INR or USD")
		}
		return s, nil
	case KColor:
		s, ok := v.(string)
		if !ok || !colorRe.MatchString(s) {
			return nil, fmt.Errorf("must be a colour in #RRGGBB form")
		}
		return strings.ToUpper(s), nil
	case KDecimal:
		s, ok := v.(string)
		if !ok || !decimalRe.MatchString(s) {
			return nil, fmt.Errorf("must be a decimal string such as \"83.1245\"")
		}
		if strings.Trim(s, "0.") == "" {
			return nil, fmt.Errorf("must be greater than zero")
		}
		return s, nil
	case KIDList:
		list, err := toIDList(v)
		if err != nil {
			return nil, err
		}
		if f.MaxLen > 0 && len(list) > f.MaxLen {
			return nil, fmt.Errorf("must have at most %d items", f.MaxLen)
		}
		return list, nil
	}
	return nil, fmt.Errorf("unsupported field kind")
}

// toDB converts a canonical value to a database argument.
func (f *Field) toDB(v any) any {
	if v == nil {
		return nil
	}
	switch f.Kind {
	case KBool:
		if v.(bool) {
			return int64(1)
		}
		return int64(0)
	case KIDList:
		b, _ := json.Marshal(v.([]string))
		return string(b)
	}
	return v
}

// fromDB converts a scanned database value back to its canonical form.
func (f *Field) fromDB(v any) any {
	switch x := v.(type) {
	case nil:
		if f.Kind == KIDList {
			return []string{}
		}
		return nil
	case []byte:
		v = string(x)
	}
	switch f.Kind {
	case KBool:
		n, _ := toInt64(v)
		return n != 0
	case KInt:
		n, _ := toInt64(v)
		return n
	case KIDList:
		var list []string
		if s, ok := v.(string); ok && s != "" {
			_ = json.Unmarshal([]byte(s), &list)
		}
		if list == nil {
			list = []string{}
		}
		return list
	}
	return v
}

func toInt64(v any) (int64, error) {
	switch n := v.(type) {
	case int64:
		return n, nil
	case int:
		return int64(n), nil
	case int32:
		return int64(n), nil
	case json.Number:
		i, err := n.Int64()
		if err != nil {
			return 0, fmt.Errorf("must be a whole number")
		}
		return i, nil
	case float64:
		// JSON numbers are exact for |n| <= 2^53, which covers every
		// realistic amount in minor units.
		if n != math.Trunc(n) || math.Abs(n) > 1<<53 {
			return 0, fmt.Errorf("must be a whole number")
		}
		return int64(n), nil
	}
	return 0, errType("number")
}

func toIDList(v any) ([]string, error) {
	var raw []any
	switch x := v.(type) {
	case []any:
		raw = x
	case []string:
		for _, s := range x {
			raw = append(raw, s)
		}
	default:
		return nil, errType("array of UUIDs")
	}
	out := make([]string, 0, len(raw))
	seen := make(map[string]bool, len(raw))
	for _, item := range raw {
		s, ok := item.(string)
		if !ok {
			return nil, errType("array of UUIDs")
		}
		n, ok := ids.Normalize(s)
		if !ok {
			return nil, fmt.Errorf("item %q is not a UUID", s)
		}
		if !seen[n] {
			seen[n] = true
			out = append(out, n)
		}
	}
	return out, nil
}

func errType(want string) error { return fmt.Errorf("must be a %s", want) }

// ValidCurrency reports whether s is an acceptable currency code.
func ValidCurrency(s string) bool { return currencyRe.MatchString(s) }
