class Solution {
public:
    int helper(vector<int> &nums){
        vector<int> prefix(nums),suffix(nums);
        int n = size(nums);
        int cnt = 0;
        for(int i = 1 ; i < n ; i++){
            prefix[i] = gcd(nums[i],prefix[i-1]);
            suffix[n-i-1] = gcd(suffix[n-i],nums[n-i-1]);
        }
        for(int i = 1 ; i < n ; i++){
            cnt += (prefix[i-1] == suffix[i]);
        }
        return cnt;
    }
    int maxValidSplits(vector<int>& nums) {
        vector<int> prefix(nums),suffix(nums);
        int n = size(nums);
        int cnt = 0;
        for(int i = 1 ; i < n ; i++){
            prefix[i] = gcd(nums[i],prefix[i-1]);
            suffix[n-i-1] = gcd(suffix[n-i],nums[n-i-1]);
        }
        set<int> st;
        for(int i = 1 ; i < n ; i++){
            if(prefix[i] != prefix[i-1])
                st.insert(i);
            if(suffix[n-i] != suffix[n-i-1])
                st.insert(n-i-1);
        }
        int ans = helper(nums);
        cout<<size(st);
        for(auto it : st){
            int idx = it;
            vector<int> tmp;
            for(int i = 0 ; i < n ; i++){
                if(i == idx) continue;
                tmp.push_back(nums[i]);
            }
            ans = max(ans,helper(tmp));
        }
        return ans;
    }
};