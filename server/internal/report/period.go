package report

import "time"

// Period arithmetic shared by budgets. It matches java.time and the apps:
// occurrence n is computed from the start date (not chained), and adding
// months clamps to the end of a shorter month, so a period anchored on the
// 31st covers 31 Jan–27 Feb, 28 Feb–30 Mar, 31 Mar–29 Apr.

const dateLayout = time.DateOnly

func parseDate(s string) (time.Time, bool) {
	t, err := time.Parse(dateLayout, s)
	return t, err == nil
}

func formatDate(t time.Time) string { return t.Format(dateLayout) }

// addMonthsClamped adds n months, clamping the day to the target month's length.
func addMonthsClamped(t time.Time, n int) time.Time {
	y, m, d := t.Date()
	first := time.Date(y, m+time.Month(n), 1, 0, 0, 0, 0, time.UTC)
	last := first.AddDate(0, 1, -1).Day()
	if d > last {
		d = last
	}
	return time.Date(first.Year(), first.Month(), d, 0, 0, 0, 0, time.UTC)
}

// periodStep maps a budget period to (unit, count). Units: "week", "month".
func periodStep(period string) (unit string, count int) {
	switch period {
	case "weekly":
		return "week", 1
	case "quarterly":
		return "month", 3
	case "yearly":
		return "month", 12
	default:
		return "month", 1
	}
}

// periodStart returns the start of period i.
func periodStart(start time.Time, period string, i int) time.Time {
	unit, n := periodStep(period)
	if unit == "week" {
		return start.AddDate(0, 0, 7*n*i)
	}
	return addMonthsClamped(start, n*i)
}

// periodIndex returns the index of the period containing date (date >= start).
func periodIndex(start time.Time, period string, date time.Time) int {
	unit, n := periodStep(period)
	var i int
	if unit == "week" {
		i = int(date.Sub(start).Hours()/24) / (7 * n)
	} else {
		months := (date.Year()-start.Year())*12 + int(date.Month()) - int(start.Month())
		i = months / n
	}
	if i < 0 {
		i = 0
	}
	for i > 0 && periodStart(start, period, i).After(date) {
		i--
	}
	for !periodStart(start, period, i+1).After(date) {
		i++
	}
	return i
}

// Window is an inclusive date range.
type Window struct {
	Start string `json:"start"`
	End   string `json:"end"`
}

// budgetWindow returns the budget period containing date, its index, and
// whether the budget is active on that date.
func budgetWindow(period, startDate, endDate string, date time.Time) (Window, int, bool) {
	start, ok := parseDate(startDate)
	if !ok {
		return Window{}, 0, false
	}
	end, hasEnd := parseDate(endDate)
	if period == "custom" {
		if !hasEnd {
			end = start
		}
		return Window{formatDate(start), formatDate(end)}, 0, true
	}
	if date.Before(start) {
		return Window{}, 0, false
	}
	i := periodIndex(start, period, date)
	ws := periodStart(start, period, i)
	we := periodStart(start, period, i+1).AddDate(0, 0, -1)
	if hasEnd && ws.After(end) {
		return Window{}, 0, false
	}
	if hasEnd && we.After(end) {
		we = end
	}
	return Window{formatDate(ws), formatDate(we)}, i, true
}
