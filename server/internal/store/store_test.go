package store_test

import (
	"context"
	"strings"
	"testing"

	"github.com/kosh-app/kosh/server/internal/entity"
	"github.com/kosh-app/kosh/server/internal/testutil"
)

// TestSchemaMatchesSpecs fails if a spec field has no column in the
// migrated database, catching a forgotten migration.
func TestSchemaMatchesSpecs(t *testing.T) {
	d := testutil.NewDB(t)
	for _, s := range entity.All() {
		q := "SELECT ledger_id, id, hlc, seq, deleted, server_updated_at, " + strings.Join(s.Columns(), ", ") +
			" FROM " + s.Name + " WHERE 1 = 0"
		rows, err := d.QueryContext(context.Background(), q)
		if err != nil {
			t.Errorf("%s: %v", s.Name, err)
			continue
		}
		rows.Close()
	}
}

func TestMigrateIsIdempotent(t *testing.T) {
	d := testutil.NewDB(t)
	n, err := d.Migrate(context.Background())
	if err != nil || n != 0 {
		t.Fatalf("second migrate applied %d, err %v", n, err)
	}
}
