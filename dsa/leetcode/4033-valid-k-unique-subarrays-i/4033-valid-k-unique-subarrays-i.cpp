class Solution {
public:
    vector<bool> validSubarrays(vector<int>& nums, int k, vector<vector<int>>& queries) {
        int n = nums.size(), q = queries.size();
        int block = max(1, (int)sqrt((double)n));

        vector<array<int,3>> qs(q);          // {l, r, original index}
        for (int i = 0; i < q; i++) qs[i] = {queries[i][0], queries[i][1], i};
        sort(qs.begin(), qs.end(), [&](auto& a, auto& b){
            int ba = a[0]/block, bb = b[0]/block;
            if (ba != bb) return ba < bb;
            return (ba & 1) ? a[1] > b[1] : a[1] < b[1];   // odd/even R sort
        });

        unordered_map<int,int> cnt;
        int distinctCnt = 0;   // # values with count > 0
        int evenCnt = 0;       // # values with count > 0 AND count even

        auto add = [&](int v){
            int c = cnt[v];
            if (c == 0)            distinctCnt++;   // newly present
            else if (c % 2 == 0)   evenCnt--;       // even -> odd
            else                   evenCnt++;       // odd  -> even
            cnt[v] = c + 1;
        };
        auto remove = [&](int v){
            int c = cnt[v];
            if (c % 2 == 0)        evenCnt--;        // even -> odd
            else if (c - 1 > 0)    evenCnt++;        // odd  -> even (still present)
            cnt[v] = c - 1;
            if (cnt[v] == 0)       distinctCnt--;    // gone
        };

        vector<bool> res(q);
        int curL = 0, curR = -1;
        for (auto& Q : qs) {
            int l = Q[0], r = Q[1];
            while (curR < r) add(nums[++curR]);
            while (curL > l) add(nums[--curL]);
            while (curR > r) remove(nums[curR--]);
            while (curL < l) remove(nums[curL++]);
            res[Q[2]] = (distinctCnt == k && evenCnt == k);
        }
        return res;
    }
};