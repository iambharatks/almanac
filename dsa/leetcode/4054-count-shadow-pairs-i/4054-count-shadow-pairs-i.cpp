class Solution {
public:
    long long shadowPairs(vector<int>& nums) {
        stack<pair<int,int>> st;
        long long res = 0;
        for(int i : nums){
            while(!st.empty() && st.top().first > i){
                st.pop();
            }
            
            if(!st.empty()){
                res += st.size();
                if(st.top().first == i)
                    res -= st.top().second;
            }   
            if(!st.empty() && st.top().first == i)
                st.push({i,st.top().second+1});
            else st.push({i,1});
        }
        return res;
    }
};

// class Solution {
// public:
//     long long shadowPairs(vector<int>& nums) {
//         stack<int> st;
//         int res = 0;
//         int n = size(nums);
//         vector<int> dp(n,0);
//         for(int i = 0 ; i < n; i++){
//             while(!st.empty() && nums[st.top()] > nums[i]){
//                 st.pop();
//             }
//             if(!st.empty() && nums[st.top()] < nums[i]){
//                 dp[i] = st.size();
//                 res += st.size();
//             }
//             st.push(i);
//         }

//         return res;
//     }
// };