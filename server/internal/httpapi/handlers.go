package httpapi

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/kosh-app/kosh/server/internal/backup"
	"github.com/kosh-app/kosh/server/internal/db"
	"github.com/kosh-app/kosh/server/internal/entity"
	"github.com/kosh-app/kosh/server/internal/ids"
	"github.com/kosh-app/kosh/server/internal/ledger"
	"github.com/kosh-app/kosh/server/internal/store"
	"github.com/kosh-app/kosh/server/internal/syncer"
)

// --- health & meta ---

func (s *Server) healthz(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) readyz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.DB.Ping(ctx); err != nil {
		writeError(w, http.StatusServiceUnavailable, "db_unavailable", "database is not reachable", nil)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
}

func (s *Server) meta(w http.ResponseWriter, r *http.Request) {
	signup, err := s.Auth.SignupAllowed(r.Context())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"name": "kosh", "version": s.Version, "api_version": 1,
		"schema_version": db.LatestSchemaVersion(), "database": s.DB.Dialect().String(),
		"signup_allowed": signup, "entities": entity.Names(),
		"limits":      map[string]int{"push_batch": syncer.MaxPushBatch, "pull_page": syncer.MaxPullLimit, "batch_ops": MaxBatchOps},
		"server_time": time.Now().UnixMilli(),
	})
}

// --- auth ---

type credentials struct {
	Email      string `json:"email"`
	Password   string `json:"password"`
	Name       string `json:"name"`
	DeviceName string `json:"device_name"`
}

func (s *Server) register(w http.ResponseWriter, r *http.Request) {
	var in credentials
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	u, token, err := s.Auth.Register(r.Context(), in.Email, in.Name, in.Password, in.DeviceName)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"user": u, "token": token})
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var in credentials
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	u, token, err := s.Auth.Login(r.Context(), in.Email, in.Password, in.DeviceName)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"user": u, "token": token})
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	if err := store.DeleteSession(r.Context(), s.DB, currentUser(r).ID, currentSession(r).ID); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) me(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"user": currentUser(r), "session": currentSession(r)})
}

func (s *Server) listSessions(w http.ResponseWriter, r *http.Request) {
	list, err := store.ListSessions(r.Context(), s.DB, currentUser(r).ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	current := currentSession(r).ID
	out := make([]map[string]any, len(list))
	for i, ss := range list {
		out[i] = map[string]any{"id": ss.ID, "device_name": ss.DeviceName, "created_at": ss.CreatedAt,
			"last_used_at": ss.LastUsedAt, "expires_at": ss.ExpiresAt, "current": ss.ID == current}
	}
	writeJSON(w, http.StatusOK, map[string]any{"sessions": out})
}

func (s *Server) deleteSession(w http.ResponseWriter, r *http.Request) {
	if err := store.DeleteSession(r.Context(), s.DB, currentUser(r).ID, r.PathValue("id")); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) changePassword(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Current string `json:"current_password"`
		New     string `json:"new_password"`
	}
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	if err := s.Auth.ChangePassword(r.Context(), currentUser(r), currentSession(r).ID, in.Current, in.New); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- ledgers ---

func (s *Server) listLedgers(w http.ResponseWriter, r *http.Request) {
	list, err := store.LedgersForUser(r.Context(), s.DB, currentUser(r).ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ledgers": list})
}

func (s *Server) createLedger(w http.ResponseWriter, r *http.Request) {
	var in ledger.CreateInput
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	l, created, err := s.Ledgers.Create(r.Context(), currentUser(r).ID, in)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, map[string]any{"ledger": l, "created": created})
}

func (s *Server) getLedger(w http.ResponseWriter, r *http.Request) {
	l, err := s.Ledgers.Get(r.Context(), r.PathValue("lid"), currentUser(r).ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ledger": l})
}

func (s *Server) updateLedger(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Name         *string `json:"name"`
		BaseCurrency *string `json:"base_currency"`
	}
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	l, err := s.Ledgers.Update(r.Context(), r.PathValue("lid"), currentUser(r).ID, in.Name, in.BaseCurrency)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ledger": l})
}

func (s *Server) deleteLedger(w http.ResponseWriter, r *http.Request) {
	// Guard against accidental deletes: the client must echo the ledger ID.
	if r.URL.Query().Get("confirm") != r.PathValue("lid") {
		s.fail(w, r, badReq("add ?confirm=<ledger id> to permanently delete this ledger and all its data"))
		return
	}
	if err := s.Ledgers.Delete(r.Context(), r.PathValue("lid"), currentUser(r).ID); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) listMembers(w http.ResponseWriter, r *http.Request) {
	list, err := s.Ledgers.Members(r.Context(), r.PathValue("lid"), currentUser(r).ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"members": list})
}

func (s *Server) addMember(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Email string `json:"email"`
		Role  string `json:"role"`
	}
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	if in.Role == "" {
		in.Role = store.RoleEditor
	}
	m, err := s.Ledgers.AddMember(r.Context(), r.PathValue("lid"), currentUser(r).ID, in.Email, in.Role)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"member": m})
}

func (s *Server) removeMember(w http.ResponseWriter, r *http.Request) {
	if err := s.Ledgers.RemoveMember(r.Context(), r.PathValue("lid"), currentUser(r).ID, r.PathValue("uid")); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// authorize checks ledger access and returns the ledger ID.
func (s *Server) authorize(w http.ResponseWriter, r *http.Request, need ledger.Access) (string, bool) {
	lid := r.PathValue("lid")
	if _, err := s.Ledgers.Authorize(r.Context(), lid, currentUser(r).ID, need); err != nil {
		s.fail(w, r, err)
		return "", false
	}
	return lid, true
}

// --- sync ---

type pushRequest struct {
	SchemaVersion int             `json:"schema_version"`
	DeviceID      string          `json:"device_id"`
	Changes       []syncer.Change `json:"changes"`
}

func (s *Server) push(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Write)
	if !ok {
		return
	}
	var in pushRequest
	if err := decode(w, r, pushBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	if in.SchemaVersion > db.LatestSchemaVersion() {
		writeError(w, http.StatusConflict, "server_outdated",
			fmt.Sprintf("this server understands schema v%d; the app sent v%d. Update the server.", db.LatestSchemaVersion(), in.SchemaVersion), nil)
		return
	}
	if len(in.Changes) > syncer.MaxPushBatch {
		s.fail(w, r, badReq(fmt.Sprintf("at most %d changes per push", syncer.MaxPushBatch)))
		return
	}
	res, err := s.Sync.Push(r.Context(), lid, in.Changes)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) pull(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Read)
	if !ok {
		return
	}
	since, err := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	if err != nil && r.URL.Query().Get("since") != "" || since < 0 {
		s.fail(w, r, badReq("since must be a non-negative integer cursor"))
		return
	}
	limit, err := queryInt(r, "limit", syncer.DefaultPullLimit, syncer.MaxPullLimit)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	res, err := s.Sync.Pull(r.Context(), lid, since, limit)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

// --- records (REST view over the same change feed, for online clients) ---

func entitySpec(w http.ResponseWriter, r *http.Request, s *Server) (*entity.Spec, bool) {
	spec, ok := entity.Lookup(r.PathValue("entity"))
	if !ok {
		writeError(w, http.StatusNotFound, "unknown_entity", "unknown entity "+strconv.Quote(r.PathValue("entity")), nil)
	}
	return spec, ok
}

func recordID(w http.ResponseWriter, r *http.Request, s *Server) (string, bool) {
	id, ok := ids.Normalize(r.PathValue("id"))
	if !ok {
		s.fail(w, r, badReq("id must be a UUID"))
	}
	return id, ok
}

func (s *Server) listRecords(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Read)
	if !ok {
		return
	}
	spec, ok := entitySpec(w, r, s)
	if !ok {
		return
	}
	limit, err := queryInt(r, "limit", 500, 2000)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if limit == 0 {
		limit = 500
	}
	list, err := store.ListRecords(r.Context(), s.DB, spec, lid, store.ListOptions{
		After: r.URL.Query().Get("after"), Limit: limit, IncludeDeleted: r.URL.Query().Get("include_deleted") == "true",
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	resp := map[string]any{"records": orEmpty(list)}
	if len(list) == limit {
		resp["next_after"] = list[len(list)-1].ID
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) getRecord(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Read)
	if !ok {
		return
	}
	spec, ok := entitySpec(w, r, s)
	if !ok {
		return
	}
	id, ok := recordID(w, r, s)
	if !ok {
		return
	}
	rec, err := store.GetRecord(r.Context(), s.DB, spec, lid, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"record": rec})
}

func (s *Server) createRecord(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Write)
	if !ok {
		return
	}
	spec, ok := entitySpec(w, r, s)
	if !ok {
		return
	}
	var in struct {
		ID   string         `json:"id"`
		Data map[string]any `json:"data"`
	}
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	if in.Data == nil {
		s.fail(w, r, badReq("data is required"))
		return
	}
	if _, ok := spec.Field("created_at"); ok && in.Data["created_at"] == nil {
		in.Data["created_at"] = time.Now().UnixMilli()
	}
	recs, err := s.Sync.Write(r.Context(), lid, []syncer.WriteOp{{Entity: spec.Name, ID: in.ID, Data: in.Data, MustNotExist: true}})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"record": recs[0]})
}

func (s *Server) updateRecord(w http.ResponseWriter, r *http.Request) {
	s.writeOne(w, r, func(spec *entity.Spec, id string) (syncer.WriteOp, error) {
		var in struct {
			Data map[string]any `json:"data"`
		}
		if err := decode(w, r, smallBody, &in); err != nil {
			return syncer.WriteOp{}, err
		}
		return syncer.WriteOp{Entity: spec.Name, ID: id, Data: in.Data, MustExist: true}, nil
	})
}

func (s *Server) deleteRecord(w http.ResponseWriter, r *http.Request) {
	s.writeOne(w, r, func(spec *entity.Spec, id string) (syncer.WriteOp, error) {
		return syncer.WriteOp{Entity: spec.Name, ID: id, Deleted: true, MustExist: true}, nil
	})
}

// restoreRecord brings a record back from the Trash with a fresh HLC, so the
// undelete syncs to every device.
func (s *Server) restoreRecord(w http.ResponseWriter, r *http.Request) {
	s.writeOne(w, r, func(spec *entity.Spec, id string) (syncer.WriteOp, error) {
		rec, err := store.GetRecord(r.Context(), s.DB, spec, r.PathValue("lid"), id)
		if err != nil {
			return syncer.WriteOp{}, err
		}
		if !rec.Deleted {
			return syncer.WriteOp{}, badReq("record is not deleted")
		}
		return syncer.WriteOp{Entity: spec.Name, ID: id}, nil
	})
}

func (s *Server) writeOne(w http.ResponseWriter, r *http.Request, build func(*entity.Spec, string) (syncer.WriteOp, error)) {
	lid, ok := s.authorize(w, r, ledger.Write)
	if !ok {
		return
	}
	spec, ok := entitySpec(w, r, s)
	if !ok {
		return
	}
	id, ok := recordID(w, r, s)
	if !ok {
		return
	}
	op, err := build(spec, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	recs, err := s.Sync.Write(r.Context(), lid, []syncer.WriteOp{op})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"record": recs[0]})
}

func (s *Server) listTransactions(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Read)
	if !ok {
		return
	}
	q := r.URL.Query()
	from, err := queryDate(r, "from", false)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	to, err := queryDate(r, "to", false)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	limit, err := queryInt(r, "limit", 100, 500)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if limit == 0 {
		limit = 100
	}
	minAmount, err := queryAmount(r, "min_amount")
	if err != nil {
		s.fail(w, r, err)
		return
	}
	maxAmount, err := queryAmount(r, "max_amount")
	if err != nil {
		s.fail(w, r, err)
		return
	}
	list, next, err := store.ListTransactions(r.Context(), s.DB, lid, store.TxFilter{
		From: from, To: to, AccountID: q.Get("account_id"), CategoryID: q.Get("category_id"),
		TagID: q.Get("tag_id"), DebtID: q.Get("debt_id"), MinAmount: minAmount, MaxAmount: maxAmount,
		Type: q.Get("type"), Status: q.Get("status"), Query: q.Get("q"),
		Deleted: q.Get("deleted") == "true", Cursor: q.Get("cursor"), Limit: limit,
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	resp := map[string]any{"transactions": list}
	if next != "" {
		resp["next_cursor"] = next
	}
	writeJSON(w, http.StatusOK, resp)
}

// --- reports ---

func (s *Server) balances(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Read)
	if !ok {
		return
	}
	asOf, err := queryDate(r, "as_of", false)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	list, err := s.Reports.Balances(r.Context(), lid, asOf)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"balances": list})
}

func (s *Server) rangeParams(w http.ResponseWriter, r *http.Request) (lid, from, to string, ok bool) {
	lid, ok = s.authorize(w, r, ledger.Read)
	if !ok {
		return
	}
	var err error
	if from, err = queryDate(r, "from", true); err == nil {
		to, err = queryDate(r, "to", true)
	}
	if err == nil && to < from {
		err = badReq("to must not be before from")
	}
	if err != nil {
		s.fail(w, r, err)
		return "", "", "", false
	}
	return lid, from, to, true
}

func (s *Server) summary(w http.ResponseWriter, r *http.Request) {
	lid, from, to, ok := s.rangeParams(w, r)
	if !ok {
		return
	}
	convertTo, err := convertParam(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	res, err := s.Reports.Summary(r.Context(), lid, from, to)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := map[string]any{"from": from, "to": to, "currencies": res}
	if convertTo != "" {
		conv, err := s.Reports.Convert(r.Context(), lid, convertTo, to, res)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		out["converted"] = conv
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) trend(w http.ResponseWriter, r *http.Request) {
	lid, from, to, ok := s.rangeParams(w, r)
	if !ok {
		return
	}
	interval := r.URL.Query().Get("interval")
	if interval == "" {
		interval = "month"
	}
	if interval != "day" && interval != "month" {
		s.fail(w, r, badReq("interval must be day or month"))
		return
	}
	res, err := s.Reports.Trend(r.Context(), lid, from, to, interval)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"from": from, "to": to, "interval": interval, "points": res})
}

// --- backup ---

func (s *Server) exportLedger(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Read)
	if !ok {
		return
	}
	l, err := store.GetLedger(r.Context(), s.DB, lid)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	name := fmt.Sprintf("kosh-ledger-%s-%s.json", lid[:8], time.Now().UTC().Format("20060102-150405"))
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="`+name+`"`)
	if err := backup.ExportLedger(r.Context(), s.DB, l, w); err != nil {
		// Headers are already sent; the truncated body fails JSON parsing on
		// the client, which is the safe outcome.
		s.Log.Error("export failed", "ledger", lid, "err", err)
	}
}

func (s *Server) importLedger(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Write)
	if !ok {
		return
	}
	body := http.MaxBytesReader(w, r.Body, s.MaxImportSize)
	reader, err := backup.NewLedgerReader(body)
	if err != nil {
		s.fail(w, r, badReq("not a LekhaMint ledger export: "+err.Error()))
		return
	}
	res, err := s.Sync.Import(r.Context(), lid, reader.Next)
	if err != nil {
		var mbe *http.MaxBytesError
		switch {
		case errors.As(err, &mbe):
			s.fail(w, r, badReq("import file too large"))
		case errors.Is(err, backup.ErrBadLedgerFile):
			s.fail(w, r, badReq(err.Error()))
		default:
			if errors.Is(err, store.ErrNotFound) {
				s.fail(w, r, err)
				return
			}
			// Parse errors from the stream are client errors; nothing was written.
			s.Log.Warn("import rolled back", "ledger", lid, "err", err)
			s.fail(w, r, badReq("import failed and nothing was changed: "+err.Error()))
		}
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func orEmpty(list []store.Record) []store.Record {
	if list == nil {
		return []store.Record{}
	}
	return list
}

// --- budgets, tags and currency conversion ---

func (s *Server) budgetProgress(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Read)
	if !ok {
		return
	}
	date, err := queryDate(r, "date", false)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if date == "" {
		date = time.Now().Format(time.DateOnly)
	}
	list, err := s.Reports.Budgets(r.Context(), lid, date)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"date": date, "budgets": list})
}

func (s *Server) tagTotals(w http.ResponseWriter, r *http.Request) {
	lid, from, to, ok := s.rangeParams(w, r)
	if !ok {
		return
	}
	list, err := s.Reports.Tags(r.Context(), lid, from, to)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"from": from, "to": to, "tags": list})
}

// convertParam validates ?convert_to=, a currency code or empty.
func convertParam(r *http.Request) (string, error) {
	c := r.URL.Query().Get("convert_to")
	if c == "" {
		return "", nil
	}
	if !entity.ValidCurrency(c) {
		return "", badReq("convert_to must be a currency code such as INR")
	}
	return c, nil
}

func (s *Server) payeeTotals(w http.ResponseWriter, r *http.Request) {
	lid, from, to, ok := s.rangeParams(w, r)
	if !ok {
		return
	}
	limit := 20
	if v := r.URL.Query().Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 500 {
			s.fail(w, r, badReq("limit must be between 1 and 500"))
			return
		}
		limit = n
	}
	list, err := s.Reports.Payees(r.Context(), lid, from, to, limit)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"from": from, "to": to, "payees": list})
}
