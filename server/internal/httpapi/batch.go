package httpapi

import (
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/kosh-app/kosh/server/internal/entity"
	"github.com/kosh-app/kosh/server/internal/ids"
	"github.com/kosh-app/kosh/server/internal/ledger"
	"github.com/kosh-app/kosh/server/internal/store"
	"github.com/kosh-app/kosh/server/internal/syncer"
)

// MaxBatchOps bounds one batch request.
const MaxBatchOps = 500

type batchOp struct {
	Op     string         `json:"op"` // create, create_if_missing, update, delete, restore
	Entity string         `json:"entity"`
	ID     string         `json:"id"`
	Data   map[string]any `json:"data"`
}

// batch applies up to MaxBatchOps writes atomically: either every op is
// applied or none is. "create_if_missing" skips records that already exist
// (including ones in the Trash), which makes re-running an import safe.
func (s *Server) batch(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Write)
	if !ok {
		return
	}
	var in struct {
		Ops []batchOp `json:"ops"`
	}
	if err := decode(w, r, pushBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	if len(in.Ops) == 0 || len(in.Ops) > MaxBatchOps {
		s.fail(w, r, badReq(fmt.Sprintf("send 1 to %d ops", MaxBatchOps)))
		return
	}
	ops := make([]syncer.WriteOp, 0, len(in.Ops))
	now := time.Now().UnixMilli()
	for i, op := range in.Ops {
		spec, ok := entity.Lookup(op.Entity)
		if !ok {
			s.fail(w, r, badReq(fmt.Sprintf("ops[%d]: unknown entity %q", i, op.Entity)))
			return
		}
		if op.ID != "" {
			id, ok := ids.Normalize(op.ID)
			if !ok {
				s.fail(w, r, badReq(fmt.Sprintf("ops[%d]: id must be a UUID", i)))
				return
			}
			op.ID = id
		}
		wo := syncer.WriteOp{Entity: spec.Name, ID: op.ID, Data: op.Data}
		switch op.Op {
		case "create", "create_if_missing":
			if op.Data == nil {
				s.fail(w, r, badReq(fmt.Sprintf("ops[%d]: data is required", i)))
				return
			}
			if _, ok := spec.Field("created_at"); ok && op.Data["created_at"] == nil {
				op.Data["created_at"] = now
			}
			wo.MustNotExist = op.Op == "create"
			wo.SkipIfExists = op.Op == "create_if_missing"
		case "update":
			wo.MustExist = true
		case "delete":
			wo.MustExist, wo.Deleted, wo.Data = true, true, nil
		case "restore":
			wo.MustExistDeleted, wo.Data = true, nil
		default:
			s.fail(w, r, badReq(fmt.Sprintf("ops[%d]: op must be create, create_if_missing, update, delete or restore", i)))
			return
		}
		if op.Op != "create" && op.Op != "create_if_missing" && op.ID == "" {
			s.fail(w, r, badReq(fmt.Sprintf("ops[%d]: id is required", i)))
			return
		}
		ops = append(ops, wo)
	}
	results, err := s.Sync.WriteResults(r.Context(), lid, ops)
	if err != nil {
		var rej *syncer.RejectionError
		if errors.As(err, &rej) {
			writeError(w, http.StatusUnprocessableEntity, "validation",
				fmt.Sprintf("%s %s: %s", rej.Entity, rej.ID, rej.Message), rej.Fields)
			return
		}
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "a record in the batch does not exist; nothing was changed", nil)
			return
		}
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": results})
}
